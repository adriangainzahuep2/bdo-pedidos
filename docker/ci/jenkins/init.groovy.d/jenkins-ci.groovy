// =============================================================================
// Configuracion de Jenkins al arrancar (init.groovy.d).
//
// Es idempotente: se puede ejecutar en cada arranque sin duplicar nada.
// Crea:
//   1. Las credenciales (el token del proveedor y el de SonarQube).
//   2. El servidor de SonarQube.
//   3. El job del pipeline, tomando el Jenkinsfile del repositorio.
// =============================================================================
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import hudson.plugins.sonar.SonarGlobalConfiguration
import hudson.plugins.sonar.SonarInstallation
import hudson.plugins.sonar.model.TriggersConfig
import hudson.util.Secret
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
// La clase de credencial de texto plano vive en el plugin "plain-credentials"
// (antes estaba en "credentials" y ya no existe).
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl

// En los scripts de inicio de Jenkins no hay un "logger" ligado, y una variable
// local no se captura desde el closure: se escribe en la salida estandar, que
// Jenkins muestra en el log del contenedor.

def jenkins = Jenkins.get()
def credenciales = SystemCredentialsProvider.getInstance().getStore()

// --- 0. Usuario local de Jenkins --------------------------------------------
// Sin el asistente de configuracion, Jenkins queda con la estrategia
// "FullControlOnceLoggedIn": el usuario anonimo lee pero no puede lanzar
// builds. Se crea un usuario local con el que disparar el pipeline.
def USUARIO_JENKINS = System.getenv('JENKINS_USUARIO') ?: 'admin'
def CLAVE_JENKINS = System.getenv('JENKINS_CLAVE') ?: 'admin'
def realm = new hudson.security.HudsonPrivateSecurityRealm(false, false, null)
def yaExiste = realm.getAllUsers().find { it.id == USUARIO_JENKINS }
if (yaExiste == null) {
    realm.createAccount(USUARIO_JENKINS, CLAVE_JENKINS)
    println("[ci-init] Usuario de Jenkins '${USUARIO_JENKINS}' creado.")
} else {
    println("[ci-init] El usuario de Jenkins '${USUARIO_JENKINS}' ya existia.")
}
jenkins.setSecurityRealm(realm)
jenkins.setAuthorizationStrategy(new hudson.security.FullControlOnceLoggedInAuthorizationStrategy())

// --- 1. Credenciales --------------------------------------------------------
def registrarCredencial = { String id, String descripcion, String valor ->
    def existente = credenciales.getCredentials(Domain.global())
            .find { it.id == id && it instanceof StringCredentialsImpl }
    if (existente != null) {
        println("[ci-init] La credencial '${id}' ya existe: se conserva.")
        return
    }
    def credencial = new StringCredentialsImpl(CredentialsScope.GLOBAL, id, descripcion,
            Secret.fromString(valor))
    credenciales.addCredentials(Domain.global(), credencial)
    println("[ci-init] Credencial '${id}' creada.")
}

registrarCredencial('bdo-proveedor-token',
        'Token del proveedor logístico (lo inyecta el pipeline por entorno)',
        System.getenv('PROVEEDOR_LOGISTICO_TOKEN') ?: 'token-de-prueba')
registrarCredencial('sonarqube-token',
        'Token de API de SonarQube para el análisis',
        System.getenv('SONAR_TOKEN') ?: '')

// --- 2. Servidor de SonarQube ------------------------------------------------
// Lo consume el paso waitForQualityGate (plugin "sonar"), que resuelve la URL
// y el token a partir de la instalacion llamada 'SonarQube'.
def urlSonar = System.getenv('SONAR_HOST_URL') ?: 'http://sonarqube:9000'
def yaConfigurado = SonarGlobalConfiguration.get().getInstallations()
        .find { it.name == 'SonarQube' }
if (yaConfigurado == null) {
    def instalacion = new SonarInstallation(
            'SonarQube',            // nombre
            urlSonar,               // URL del servidor (red interna del stack)
            'sonarqube-token',      // credencial con el token
            null,                   // token heredado (obsoleto: se usa la credencial)
            '',                     // version del mojo
            '',                     // propiedades adicionales
            '',                     // propiedades adicionales del analisis
            '',                     // secreto del webhook
            new TriggersConfig())
    SonarGlobalConfiguration.get().setInstallations(instalacion)
    println("[ci-init] Servidor de SonarQube 'SonarQube' configurado en ${urlSonar}.")
} else {
    println("[ci-init] El servidor de SonarQube 'SonarQube' ya estaba configurado.")
}

// --- 3. Job del pipeline -----------------------------------------------------
def nombreJob = 'bdo-pedidos'
def repositorio = System.getenv('REPO_GIT') ?: 'https://github.com/adriangainzahuep2/bdo-pedidos.git'
def rama = System.getenv('REPO_RAMA') ?: 'main'

def job = jenkins.getItemByFullName(nombreJob, WorkflowJob)
def scm = new GitSCM(
        [new UserRemoteConfig(repositorio, null, null, null)],
        [new BranchSpec("*/${rama}")],
        null,   // navegador del repositorio
        '',     // herramienta de git
        []      // extensiones
)
// El pipeline vive en el repositorio: "Pipeline script from SCM".
// El constructor de CpsScmFlowDefinition toma (SCM, ruta del script).
def definicion = new CpsScmFlowDefinition(scm, 'Jenkinsfile')
definicion.setLightweight(true)

if (job == null) {
    job = jenkins.createProject(WorkflowJob, nombreJob)
    println("[ci-init] Job '${nombreJob}' creado.")
} else {
    println("[ci-init] El job '${nombreJob}' ya existe: se actualiza su definicion.")
}
job.setDefinition(definicion)
job.setDescription("Pipeline de bdo-pedidos (${repositorio}, rama ${rama}). " +
        "Las etapas son las del Jenkinsfile del repositorio.")
job.save()
println("[ci-init] Job '${nombreJob}' configurado: ${repositorio} (${rama}), script Jenkinsfile.")
