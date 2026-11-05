/*
 * Consola de pedidos: JavaScript sin dependencias (fetch + DOM).
 *
 * Nota de seguridad: en un navegador nunca se usa client_credentials (el secreto del cliente
 * acabaria en el codigo descargable). En produccion esta consola usaria el flujo
 * authorization_code + PKCE contra el servidor de autorizacion; en esta demo el token se pega
 * a mano.
 */
(function () {
    "use strict";

    var BASE = window.location.origin && window.location.hostname
        ? window.location.protocol + "//" + window.location.host
        : "http://localhost:8080";
    document.getElementById("base").textContent = BASE;

    var token = "";
    var idPedidoCreado = null;

    function $(id) { return document.getElementById(id); }

    function cabeceraJson() {
        return { "Content-Type": "application/json", "Authorization": "Bearer " + token };
    }

    function mostrar(elemento, texto) {
        $(elemento).textContent = typeof texto === "string" ? texto : JSON.stringify(texto, null, 2);
    }

    function peticion(metodo, ruta, cuerpo) {
        var opciones = { method: metodo, headers: cabeceraJson() };
        if (cuerpo) {
            opciones.body = JSON.stringify(cuerpo);
        }
        return fetch(BASE + ruta, opciones).then(function (respuesta) {
            var tipo = respuesta.headers.get("Content-Type") || "";
            if (tipo.indexOf("application/json") >= 0) {
                return respuesta.json().then(function (datos) {
                    return { ok: respuesta.ok, estado: respuesta.status, datos: datos };
                });
            }
            return respuesta.text().then(function (texto) {
                return { ok: respuesta.ok, estado: respuesta.status, datos: texto };
            });
        });
    }

    function lineaVacia() {
        return { producto: "", cantidad: 1, precioUnitario: 0, pesoUnitarioKg: 0.5 };
    }

    function pintarLineas() {
        var cuerpo = $("lineas");
        cuerpo.innerHTML = "";
        var lineas = leerLineas();
        lineas.forEach(function (linea, indice) {
            var fila = document.createElement("tr");

            function celda(contenido, editable, tipo) {
                var td = document.createElement("td");
                if (editable) {
                    var input = document.createElement("input");
                    input.type = tipo || "text";
                    input.value = contenido;
                    input.dataset.campo = editable;
                    input.dataset.indice = indice;
                    input.addEventListener("input", guardarLineas);
                    td.appendChild(input);
                } else {
                    td.textContent = contenido;
                }
                return td;
            }

            fila.appendChild(celda(linea.producto, "producto"));
            fila.appendChild(celda(linea.cantidad, "cantidad", "number"));
            fila.appendChild(celda(linea.precioUnitario, "precioUnitario", "number"));
            fila.appendChild(celda(linea.pesoUnitarioKg, "pesoUnitarioKg", "number"));

            var tdBorrar = document.createElement("td");
            var boton = document.createElement("button");
            boton.textContent = "✕";
            boton.className = "secundario";
            boton.addEventListener("click", function () {
                var actuales = leerLineas();
                actuales.splice(indice, 1);
                sessionStorage.setItem("lineas", JSON.stringify(actuales));
                pintarLineas();
            });
            tdBorrar.appendChild(boton);
            fila.appendChild(tdBorrar);

            cuerpo.appendChild(fila);
        });
    }

    function leerLineas() {
        var guardadas = sessionStorage.getItem("lineas");
        if (guardadas) {
            return JSON.parse(guardadas);
        }
        var porDefecto = [
            { producto: "Teclado mecanico", cantidad: 2, precioUnitario: 45.99, pesoUnitarioKg: 0.8 },
            { producto: "Raton inalambrico", cantidad: 1, precioUnitario: 19.5, pesoUnitarioKg: 0.1 }
        ];
        sessionStorage.setItem("lineas", JSON.stringify(porDefecto));
        return porDefecto;
    }

    function guardarLineas() {
        var entradas = document.querySelectorAll("#lineas input");
        var lineas = leerLineas();
        entradas.forEach(function (input) {
            var indice = parseInt(input.dataset.indice, 10);
            var campo = input.dataset.campo;
            var valor = input.value;
            lineas[indice][campo] = (campo === "producto") ? valor : parseFloat(valor || "0");
        });
        sessionStorage.setItem("lineas", JSON.stringify(lineas));
    }

    $("guardar-token").addEventListener("click", function () {
        token = $("token").value.trim();
        sessionStorage.setItem("token", token);
        var estado = $("estado-token");
        estado.textContent = token ? "Token guardado (" + token.length + " caracteres)" : "Falta el token";
        estado.className = "estado " + (token ? "ok" : "error");
    });

    $("anadir-linea").addEventListener("click", function () {
        var lineas = leerLineas();
        lineas.push(lineaVacia());
        sessionStorage.setItem("lineas", JSON.stringify(lineas));
        pintarLineas();
    });

    $("crear-cliente").addEventListener("click", function () {
        var cuerpo = { nombre: $("cliente-nombre").value, email: $("cliente-email").value };
        peticion("POST", "/api/clientes", cuerpo).then(function (r) {
            mostrar("salida-cliente", r.datos);
            if (r.ok) {
                $("pedido-cliente-id").value = r.datos.id;
            }
        });
    });

    $("crear-pedido").addEventListener("click", function () {
        var cuerpo = {
            clienteId: parseInt($("pedido-cliente-id").value, 10),
            codigoPostalDestino: $("pedido-cp").value,
            lineas: leerLineas()
        };
        peticion("POST", "/api/pedidos", cuerpo).then(function (r) {
            mostrar("salida-pedido", r.datos);
            if (r.ok) {
                idPedidoCreado = r.datos.id;
                $("confirmar-id").value = r.datos.id;
            }
        });
    });

    $("confirmar").addEventListener("click", function () {
        var id = $("confirmar-id").value;
        peticion("POST", "/api/pedidos/" + id + "/confirmacion").then(function (r) {
            mostrar("salida-confirmacion", r.datos);
        });
    });

    $("consultar").addEventListener("click", function () {
        var id = $("confirmar-id").value;
        peticion("GET", "/api/pedidos/" + id).then(function (r) {
            mostrar("salida-confirmacion", r.datos);
        });
    });

    // Estado inicial
    token = sessionStorage.getItem("token") || "";
    $("token").value = token;
    pintarLineas();
})();
