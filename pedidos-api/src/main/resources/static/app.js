/*
 * Consola de pedidos: JavaScript sin dependencias ni paso de build (fetch + DOM).
 *
 * Se sirve a traves del API Gateway (http://localhost:8080/), de modo que la pagina y la
 * API comparten origen y todas las llamadas pasan por el gateway (JWT, limite de
 * peticiones, correlacion).
 *
 * Inicio de sesion: authorization_code + PKCE contra el servidor de autorizacion, con el
 * cliente publico "consola-web". El navegador nunca guarda un secreto de cliente; la
 * criptografia y la construccion de las URL estan en pkce.js (probado con node --test).
 *
 * Seguridad: los datos que llegan del servidor se pintan siempre con textContent (nunca
 * innerHTML), y el token vive en sessionStorage (se pierde al cerrar la pestana).
 */
(function () {
    "use strict";

    var Pkce = window.Pkce;

    var meta = document.querySelector('meta[name="servidor-autorizacion"]');
    var CONFIG = {
        api: window.location.origin,
        servidorAutorizacion: (meta && meta.content)
            || (window.location.protocol + "//" + window.location.hostname + ":9000"),
        clientId: "consola-web",
        redirectUri: window.location.origin + "/index.html",
        scope: "pedidos.read pedidos.write clientes.read clientes.write"
    };

    var CLAVE_TOKEN = "consola.token";
    var CLAVE_VERIFICADOR = "consola.pkce.verificador";
    var CLAVE_STATE = "consola.pkce.state";
    var CLAVE_LINEAS = "consola.lineas";

    var formatoEuros = new Intl.NumberFormat("es-ES", { style: "currency", currency: "EUR" });

    function $(id) {
        return document.getElementById(id);
    }

    function leerAlmacen(clave) {
        try {
            return window.sessionStorage.getItem(clave);
        } catch (e) {
            return null;
        }
    }

    function escribirAlmacen(clave, valor) {
        try {
            if (valor === null || valor === undefined) {
                window.sessionStorage.removeItem(clave);
            } else {
                window.sessionStorage.setItem(clave, valor);
            }
        } catch (e) {
            // Almacenamiento no disponible (modo privado estricto): la consola sigue
            // funcionando, solo pierde el estado al recargar.
        }
    }

    // -------------------------------------------------------------------------------
    // Sesion
    // -------------------------------------------------------------------------------

    function tokenActual() {
        return leerAlmacen(CLAVE_TOKEN) || "";
    }

    function pintarSesion() {
        var token = tokenActual();
        var carga = Pkce.leerJwt(token);
        var estado = $("estado-sesion");
        var cerrar = $("cerrar-sesion");
        if (!token) {
            estado.textContent = "Sin sesión: inicia sesión o pega un token.";
            estado.className = "estado";
            cerrar.hidden = true;
            return;
        }
        if (carga && Pkce.haCaducado(carga, Date.now())) {
            estado.textContent = "El token ha caducado: vuelve a iniciar sesión.";
            estado.className = "estado error";
            cerrar.hidden = false;
            return;
        }
        var descripcion = carga
            ? "Sesión de " + (carga.sub || "?") + " · permisos: " + (carga.scope || "-")
                + (carga.exp ? " · caduca a las " + new Date(carga.exp * 1000).toLocaleTimeString("es-ES") : "")
            : "Token guardado (" + token.length + " caracteres)";
        estado.textContent = descripcion;
        estado.className = "estado ok";
        cerrar.hidden = false;
    }

    function iniciarSesion() {
        var verificador = Pkce.aleatorio(window.crypto);
        var state = Pkce.aleatorio(window.crypto);
        escribirAlmacen(CLAVE_VERIFICADOR, verificador);
        escribirAlmacen(CLAVE_STATE, state);
        Pkce.calcularDesafio(window.crypto, verificador).then(function (desafio) {
            window.location.assign(Pkce.urlDeAutorizacion({
                servidor: CONFIG.servidorAutorizacion,
                clientId: CONFIG.clientId,
                redirectUri: CONFIG.redirectUri,
                scope: CONFIG.scope,
                state: state,
                desafio: desafio
            }));
        }).catch(function (error) {
            mostrarError("estado-sesion", "No se pudo iniciar sesión: " + error.message);
        });
    }

    /** Si la pagina es la vuelta del servidor de autorizacion, canjea el codigo por el token. */
    function completarInicioDeSesion() {
        var codigo;
        try {
            codigo = Pkce.leerRespuesta(window.location.search, leerAlmacen(CLAVE_STATE));
        } catch (error) {
            limpiarUrl();
            mostrarError("estado-sesion", error.message);
            return Promise.resolve();
        }
        if (!codigo) {
            return Promise.resolve();
        }
        var verificador = leerAlmacen(CLAVE_VERIFICADOR);
        escribirAlmacen(CLAVE_VERIFICADOR, null);
        escribirAlmacen(CLAVE_STATE, null);
        limpiarUrl();

        return fetch(CONFIG.servidorAutorizacion + "/oauth2/token", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: Pkce.cuerpoDeCanje({
                code: codigo,
                redirectUri: CONFIG.redirectUri,
                clientId: CONFIG.clientId,
                verificador: verificador
            })
        }).then(function (respuesta) {
            return respuesta.json().then(function (datos) {
                if (!respuesta.ok || !datos.access_token) {
                    throw new Error("El servidor de autorización rechazó el código: "
                        + (datos.error || respuesta.status));
                }
                escribirAlmacen(CLAVE_TOKEN, datos.access_token);
                pintarSesion();
            });
        }).catch(function (error) {
            mostrarError("estado-sesion", "No se pudo completar el inicio de sesión: " + error.message);
        });
    }

    function limpiarUrl() {
        window.history.replaceState({}, document.title, window.location.pathname);
    }

    // -------------------------------------------------------------------------------
    // Llamadas a la API
    // -------------------------------------------------------------------------------

    function peticion(metodo, ruta, cuerpo) {
        var cabeceras = { "Accept": "application/json" };
        var token = tokenActual();
        if (token) {
            cabeceras.Authorization = "Bearer " + token;
        }
        var opciones = { method: metodo, headers: cabeceras };
        if (cuerpo !== undefined) {
            cabeceras["Content-Type"] = "application/json";
            opciones.body = JSON.stringify(cuerpo);
        }
        return fetch(CONFIG.api + ruta, opciones).then(function (respuesta) {
            var tipo = respuesta.headers.get("Content-Type") || "";
            var lectura = tipo.indexOf("json") >= 0 ? respuesta.json() : respuesta.text();
            return lectura.then(function (datos) {
                if (respuesta.status === 401) {
                    datos = "401: falta el token o ha caducado. Inicia sesión de nuevo.";
                } else if (respuesta.status === 403) {
                    datos = "403: el token no tiene el permiso necesario.";
                }
                return { ok: respuesta.ok, estado: respuesta.status, datos: datos };
            });
        }).catch(function (error) {
            return { ok: false, estado: 0, datos: "No se pudo contactar con la API: " + error.message };
        });
    }

    function mostrar(idSalida, resultado) {
        var salida = $(idSalida);
        var texto = typeof resultado.datos === "string"
            ? resultado.datos
            : JSON.stringify(resultado.datos, null, 2);
        salida.textContent = (resultado.estado ? "HTTP " + resultado.estado + "\n" : "") + texto;
        salida.className = "salida " + (resultado.ok ? "ok" : "error");
    }

    function mostrarError(idElemento, mensaje) {
        var elemento = $(idElemento);
        elemento.textContent = mensaje;
        elemento.className = elemento.className.indexOf("salida") >= 0 ? "salida error" : "estado error";
    }

    function euros(valor) {
        return valor === null || valor === undefined ? "—" : formatoEuros.format(Number(valor));
    }

    /** Resumen legible del pedido encima del JSON completo. */
    function pintarResumen(idResumen, pedido) {
        var resumen = $(idResumen);
        if (!pedido || typeof pedido !== "object" || pedido.id === undefined) {
            resumen.hidden = true;
            return;
        }
        resumen.hidden = false;
        resumen.textContent = "Pedido " + pedido.id + " · " + pedido.estado
            + " · productos " + euros(pedido.importeProductos)
            + " · envío " + euros(pedido.costeEnvio)
            + " · total " + euros(pedido.importeTotal)
            + (pedido.plazoEnvioDias ? " · plazo " + pedido.plazoEnvioDias + " días" : "")
            + (pedido.ultimoError ? " · último error: " + pedido.ultimoError : "");
    }

    // -------------------------------------------------------------------------------
    // Lineas del pedido
    // -------------------------------------------------------------------------------

    function lineasPorDefecto() {
        return [
            { producto: "Teclado mecanico", cantidad: 2, precioUnitario: 45.99, pesoUnitarioKg: 0.8 },
            { producto: "Raton inalambrico", cantidad: 1, precioUnitario: 19.5, pesoUnitarioKg: 0.1 }
        ];
    }

    function leerLineas() {
        try {
            var guardadas = JSON.parse(leerAlmacen(CLAVE_LINEAS));
            if (Array.isArray(guardadas)) {
                return guardadas;
            }
        } catch (e) {
            // Contenido corrupto: se vuelve a las lineas por defecto.
        }
        return lineasPorDefecto();
    }

    function guardarLineas(lineas) {
        escribirAlmacen(CLAVE_LINEAS, JSON.stringify(lineas));
    }

    /** Convierte el valor de un campo al tipo que espera la API (la cantidad es entera). */
    function valorDeCampo(campo, texto) {
        if (campo === "producto") {
            return texto;
        }
        if (campo === "cantidad") {
            return parseInt(texto, 10);
        }
        return texto === "" ? null : Number(texto);
    }

    function pintarLineas() {
        var cuerpo = $("lineas");
        while (cuerpo.firstChild) {
            cuerpo.removeChild(cuerpo.firstChild);
        }
        var lineas = leerLineas();
        lineas.forEach(function (linea, indice) {
            var fila = document.createElement("tr");
            [
                { campo: "producto", tipo: "text", paso: null },
                { campo: "cantidad", tipo: "number", paso: "1" },
                { campo: "precioUnitario", tipo: "number", paso: "0.0001" },
                { campo: "pesoUnitarioKg", tipo: "number", paso: "0.001" }
            ].forEach(function (definicion) {
                var celda = document.createElement("td");
                var entrada = document.createElement("input");
                entrada.type = definicion.tipo;
                if (definicion.paso) {
                    entrada.step = definicion.paso;
                    entrada.min = definicion.campo === "cantidad" ? "1" : "0";
                }
                entrada.value = linea[definicion.campo] === null || linea[definicion.campo] === undefined
                    ? "" : String(linea[definicion.campo]);
                entrada.setAttribute("aria-label", definicion.campo + " de la línea " + (indice + 1));
                entrada.addEventListener("input", function () {
                    var actuales = leerLineas();
                    actuales[indice][definicion.campo] = valorDeCampo(definicion.campo, entrada.value);
                    guardarLineas(actuales);
                });
                celda.appendChild(entrada);
                fila.appendChild(celda);
            });

            var celdaBorrar = document.createElement("td");
            var borrar = document.createElement("button");
            borrar.type = "button";
            borrar.textContent = "✕";
            borrar.className = "secundario";
            borrar.setAttribute("aria-label", "Quitar la línea " + (indice + 1));
            borrar.addEventListener("click", function () {
                var actuales = leerLineas();
                actuales.splice(indice, 1);
                guardarLineas(actuales);
                pintarLineas();
            });
            celdaBorrar.appendChild(borrar);
            fila.appendChild(celdaBorrar);
            cuerpo.appendChild(fila);
        });
    }

    // -------------------------------------------------------------------------------
    // Acciones
    // -------------------------------------------------------------------------------

    $("iniciar-sesion").addEventListener("click", iniciarSesion);

    $("cerrar-sesion").addEventListener("click", function () {
        escribirAlmacen(CLAVE_TOKEN, null);
        pintarSesion();
    });

    $("guardar-token").addEventListener("click", function () {
        escribirAlmacen(CLAVE_TOKEN, $("token").value.trim() || null);
        $("token").value = "";
        pintarSesion();
    });

    $("anadir-linea").addEventListener("click", function () {
        var lineas = leerLineas();
        lineas.push({ producto: "", cantidad: 1, precioUnitario: 0, pesoUnitarioKg: 0.5 });
        guardarLineas(lineas);
        pintarLineas();
    });

    $("crear-cliente").addEventListener("click", function () {
        var cuerpo = { nombre: $("cliente-nombre").value, email: $("cliente-email").value };
        peticion("POST", "/api/clientes", cuerpo).then(function (resultado) {
            mostrar("salida-cliente", resultado);
            if (resultado.ok) {
                $("pedido-cliente-id").value = resultado.datos.id;
            }
        });
    });

    $("crear-pedido").addEventListener("click", function () {
        // Sin ningun campo de importe: los calcula el servidor.
        var cuerpo = {
            clienteId: parseInt($("pedido-cliente-id").value, 10),
            codigoPostalDestino: $("pedido-cp").value,
            lineas: leerLineas()
        };
        peticion("POST", "/api/pedidos", cuerpo).then(function (resultado) {
            mostrar("salida-pedido", resultado);
            pintarResumen("resumen-pedido", resultado.ok ? resultado.datos : null);
            if (resultado.ok) {
                $("confirmar-id").value = resultado.datos.id;
            }
        });
    });

    function accionSobrePedido(metodo, sufijo) {
        var id = parseInt($("confirmar-id").value, 10);
        if (!(id > 0)) {
            mostrar("salida-confirmacion", { ok: false, estado: 0, datos: "Indica el id del pedido." });
            return;
        }
        $("salida-confirmacion").textContent = "Esperando al servidor...";
        peticion(metodo, "/api/pedidos/" + id + sufijo).then(function (resultado) {
            mostrar("salida-confirmacion", resultado);
            pintarResumen("resumen-confirmacion", resultado.ok ? resultado.datos : null);
        });
    }

    $("confirmar").addEventListener("click", function () {
        accionSobrePedido("POST", "/confirmacion");
    });
    $("cancelar").addEventListener("click", function () {
        accionSobrePedido("POST", "/cancelacion");
    });
    $("consultar").addEventListener("click", function () {
        accionSobrePedido("GET", "");
    });

    // -------------------------------------------------------------------------------
    // Estado inicial
    // -------------------------------------------------------------------------------

    $("base").textContent = CONFIG.api;
    $("servidor-autorizacion").textContent = CONFIG.servidorAutorizacion;
    pintarLineas();
    pintarSesion();
    completarInicioDeSesion();
})();
