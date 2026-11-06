/*
 * Funciones puras del inicio de sesion OAuth2 (authorization_code + PKCE, RFC 7636).
 *
 * Sin dependencias y sin DOM: el navegador las usa desde app.js (window.Pkce) y los tests
 * las importan con Node (node --test), de modo que la parte delicada de la consola (la
 * criptografia y la construccion de las URL) esta probada.
 *
 * Por que PKCE: la consola es un cliente publico (codigo que se descarga en el navegador),
 * asi que no puede guardar un secreto. client_credentials en el navegador expondria el
 * secreto del cliente; PKCE demuestra la posesion del "verificador" sin secreto.
 */
(function (raiz, fabrica) {
    "use strict";
    if (typeof module === "object" && module.exports) {
        module.exports = fabrica();
    } else {
        raiz.Pkce = fabrica();
    }
})(typeof self !== "undefined" ? self : this, function () {
    "use strict";

    /** Codifica bytes en base64url sin relleno (RFC 4648 sec. 5), como exige PKCE. */
    function base64Url(bytes) {
        var binario = "";
        for (var i = 0; i < bytes.length; i++) {
            binario += String.fromCharCode(bytes[i]);
        }
        return btoa(binario).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    }

    /** Cadena aleatoria criptograficamente segura (32 bytes -> 43 caracteres base64url). */
    function aleatorio(cripto) {
        var bytes = new Uint8Array(32);
        cripto.getRandomValues(bytes);
        return base64Url(bytes);
    }

    /** code_challenge = BASE64URL(SHA-256(code_verifier)), metodo S256. */
    function calcularDesafio(cripto, verificador) {
        var datos = new TextEncoder().encode(verificador);
        return cripto.subtle.digest("SHA-256", datos).then(function (hash) {
            return base64Url(new Uint8Array(hash));
        });
    }

    /** URL de /oauth2/authorize con todos los parametros del flujo. */
    function urlDeAutorizacion(opciones) {
        var parametros = new URLSearchParams({
            response_type: "code",
            client_id: opciones.clientId,
            redirect_uri: opciones.redirectUri,
            scope: opciones.scope,
            state: opciones.state,
            code_challenge: opciones.desafio,
            code_challenge_method: "S256"
        });
        return opciones.servidor + "/oauth2/authorize?" + parametros.toString();
    }

    /** Cuerpo x-www-form-urlencoded del canje del codigo por el token. */
    function cuerpoDeCanje(opciones) {
        return new URLSearchParams({
            grant_type: "authorization_code",
            code: opciones.code,
            redirect_uri: opciones.redirectUri,
            client_id: opciones.clientId,
            code_verifier: opciones.verificador
        }).toString();
    }

    /**
     * Lee la vuelta del servidor de autorizacion (?code=...&state=...). Devuelve null si la
     * URL no es una vuelta; lanza un error si el servidor devolvio un error o si el state no
     * coincide (proteccion CSRF del flujo).
     */
    function leerRespuesta(busqueda, stateEsperado) {
        var parametros = new URLSearchParams(busqueda);
        if (parametros.has("error")) {
            throw new Error("El servidor de autorizacion devolvio " + parametros.get("error")
                + (parametros.get("error_description") ? ": " + parametros.get("error_description") : ""));
        }
        if (!parametros.has("code")) {
            return null;
        }
        if (!stateEsperado || parametros.get("state") !== stateEsperado) {
            throw new Error("El parametro state no coincide: se descarta la respuesta");
        }
        return parametros.get("code");
    }

    /** Decodifica (sin verificar: lo verifica el servidor) la carga util de un JWT. */
    function leerJwt(token) {
        var partes = String(token || "").split(".");
        if (partes.length !== 3) {
            return null;
        }
        try {
            var base64 = partes[1].replace(/-/g, "+").replace(/_/g, "/");
            while (base64.length % 4) {
                base64 += "=";
            }
            var binario = atob(base64);
            var bytes = new Uint8Array(binario.length);
            for (var i = 0; i < binario.length; i++) {
                bytes[i] = binario.charCodeAt(i);
            }
            return JSON.parse(new TextDecoder().decode(bytes));
        } catch (e) {
            return null;
        }
    }

    /** true si el token ha caducado (o no tiene "exp"). */
    function haCaducado(carga, ahoraMs) {
        return !carga || typeof carga.exp !== "number" || carga.exp * 1000 <= ahoraMs;
    }

    return {
        base64Url: base64Url,
        aleatorio: aleatorio,
        calcularDesafio: calcularDesafio,
        urlDeAutorizacion: urlDeAutorizacion,
        cuerpoDeCanje: cuerpoDeCanje,
        leerRespuesta: leerRespuesta,
        leerJwt: leerJwt,
        haCaducado: haCaducado
    };
});
