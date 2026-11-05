#!/usr/bin/env python3
"""
Proveedor logistico simulado para las pruebas locales y la verificacion de extremo a extremo.

Implementa el contrato real descrito en el enunciado:

    POST /api/tarifas
    Authorization: Bearer <token>
    Content-Type: application/json
    {"codigoPostalDestino": "43840", "pesoKg": 3.5, "numBultos": 2}
    -> 200 {"coste": 7.95, "moneda": "EUR", "plazoDias": 2}

Con --token exige ese token exacto (401 si no coincide); sin --token acepta cualquiera.

Reproduce ademas los dos modos de fallo del enunciado:
  * --latencia <ms>   : el proveedor tarda (por defecto hasta 5 s).
  * --fallo 503       : responde 503 de forma intermitente.

Uso:
    python3 scripts/mock-proveedor.py --puerto 8090 --token secreto
    python3 scripts/mock-proveedor.py --puerto 8090 --fallo 503 --proporcion 0.5
"""

import argparse
import json
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ESTADO = {"token": None, "latencia": 0.0, "fallo": None, "proporcion": 1.0, "peticiones": 0, "llamadas_ok": 0}
CERROJO = threading.Lock()


class ProveedorHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, formato, *args):  # noqa: A002 - firma impuesta por la clase base
        print("[proveedor] " + formato % args, flush=True)

    def _responder(self, codigo, cuerpo, cabeceras_extra=None):
        datos = json.dumps(cuerpo, ensure_ascii=False).encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(datos)))
        for clave, valor in (cabeceras_extra or {}).items():
            self.send_header(clave, valor)
        self.end_headers()
        self.wfile.write(datos)

    def do_POST(self):  # noqa: N802 - nombre impuesto por BaseHTTPRequestHandler
        if self.path != "/api/tarifas":
            self._responder(404, {"error": "ruta no encontrada"})
            return

        longitud = int(self.headers.get("Content-Length", "0"))
        cuerpo = self.rfile.read(longitud) if longitud else b"{}"

        autorizacion = self.headers.get("Authorization", "")
        if not autorizacion.startswith("Bearer "):
            self._responder(401, {"error": "falta el token"})
            return
        if ESTADO["token"] and autorizacion[len("Bearer "):] != ESTADO["token"]:
            self._responder(401, {"error": "token invalido"})
            return

        with CERROJO:
            ESTADO["peticiones"] += 1
            numero = ESTADO["peticiones"]

        if ESTADO["latencia"] > 0:
            time.sleep(ESTADO["latencia"])

        if ESTADO["fallo"] is not None and random.random() < ESTADO["proporcion"]:
            self._responder(ESTADO["fallo"], {"error": "servicio no disponible"})
            return

        try:
            peticion = json.loads(cuerpo.decode("utf-8"))
        except json.JSONDecodeError:
            self._responder(400, {"error": "json invalido"})
            return

        for campo in ("codigoPostalDestino", "pesoKg", "numBultos"):
            if campo not in peticion:
                self._responder(400, {"error": f"falta el campo {campo}"})
                return

        with CERROJO:
            ESTADO["llamadas_ok"] += 1

        # Coste simulado: base + 0.90 por kg + 1.50 por bulto (redondeo a centimos).
        base = 3.95
        peso = float(peticion["pesoKg"])
        bultos = int(peticion["numBultos"])
        coste = round(base + 0.90 * peso + 1.50 * bultos, 2)
        plazo = 2 if coste < 50 else 4

        print(f"[proveedor] peticion #{numero}: {peticion} -> {coste} EUR", flush=True)
        self._responder(200, {"coste": coste, "moneda": "EUR", "plazoDias": plazo})

    def do_GET(self):  # noqa: N802
        if self.path == "/admin/estadisticas":
            with CERROJO:
                estadisticas = {clave: valor for clave, valor in ESTADO.items() if clave != "token"}
            self._responder(200, estadisticas)
            return
        self._responder(404, {"error": "ruta no encontrada"})


def main():
    analizador = argparse.ArgumentParser(description="Proveedor logistico simulado")
    analizador.add_argument("--puerto", type=int, default=8090)
    analizador.add_argument("--token", default=None,
                            help="token Bearer exigido; si se omite, se acepta cualquier token")
    analizador.add_argument("--latencia", type=float, default=0.0,
                            help="segundos de latencia antes de responder")
    analizador.add_argument("--fallo", type=int, default=None,
                            help="codigo HTTP de error intermitente (por ejemplo 503)")
    analizador.add_argument("--proporcion", type=float, default=1.0,
                            help="proporcion de peticiones que fallan (0.0 - 1.0)")
    argumentos = analizador.parse_args()

    ESTADO["token"] = argumentos.token or None
    ESTADO["latencia"] = argumentos.latencia
    ESTADO["fallo"] = argumentos.fallo
    ESTADO["proporcion"] = argumentos.proporcion

    servidor = ThreadingHTTPServer(("0.0.0.0", argumentos.puerto), ProveedorHandler)
    print(f"[proveedor] escuchando en http://0.0.0.0:{argumentos.puerto}/api/tarifas "
          f"(latencia={argumentos.latencia}s, fallo={argumentos.fallo})", flush=True)
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("[proveedor] detenido", flush=True)


if __name__ == "__main__":
    main()
