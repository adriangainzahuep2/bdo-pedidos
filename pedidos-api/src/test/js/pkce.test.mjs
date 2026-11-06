// Tests de las funciones PKCE de la consola web (Node 18+, sin dependencias).
// Se ejecutan con ./gradlew :pedidos-api:testJavaScript (incluido en "check") o con:
//   node --test pedidos-api/src/test/js/pkce.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { webcrypto } from "node:crypto";

const require = createRequire(import.meta.url);
const Pkce = require("../../main/resources/static/pkce.js");

test("base64url sin relleno ni caracteres + y /", () => {
    assert.equal(Pkce.base64Url(new Uint8Array([251, 255, 191])), "-_-_");
    assert.equal(Pkce.base64Url(new Uint8Array([1])), "AQ");
});

test("el verificador es aleatorio y cumple RFC 7636 (43 caracteres del alfabeto permitido)", () => {
    const a = Pkce.aleatorio(webcrypto);
    const b = Pkce.aleatorio(webcrypto);
    assert.match(a, /^[A-Za-z0-9\-_]{43}$/);
    assert.notEqual(a, b);
});

test("el desafio S256 coincide con el ejemplo del RFC 7636 (apendice B)", async () => {
    const desafio = await Pkce.calcularDesafio(webcrypto, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
    assert.equal(desafio, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
});

test("la URL de autorizacion lleva todos los parametros del flujo", () => {
    const url = new URL(Pkce.urlDeAutorizacion({
        servidor: "http://localhost:9000",
        clientId: "consola-web",
        redirectUri: "http://localhost:8080/index.html",
        scope: "pedidos.read pedidos.write",
        state: "estado-1",
        desafio: "desafio-1"
    }));
    assert.equal(url.origin + url.pathname, "http://localhost:9000/oauth2/authorize");
    assert.equal(url.searchParams.get("response_type"), "code");
    assert.equal(url.searchParams.get("client_id"), "consola-web");
    assert.equal(url.searchParams.get("redirect_uri"), "http://localhost:8080/index.html");
    assert.equal(url.searchParams.get("scope"), "pedidos.read pedidos.write");
    assert.equal(url.searchParams.get("state"), "estado-1");
    assert.equal(url.searchParams.get("code_challenge"), "desafio-1");
    assert.equal(url.searchParams.get("code_challenge_method"), "S256");
});

test("el canje del codigo envia el verificador y no envia ningun secreto", () => {
    const cuerpo = new URLSearchParams(Pkce.cuerpoDeCanje({
        code: "codigo-1", redirectUri: "http://localhost:8080/index.html",
        clientId: "consola-web", verificador: "verificador-1"
    }));
    assert.equal(cuerpo.get("grant_type"), "authorization_code");
    assert.equal(cuerpo.get("code"), "codigo-1");
    assert.equal(cuerpo.get("code_verifier"), "verificador-1");
    assert.equal(cuerpo.get("client_id"), "consola-web");
    assert.equal(cuerpo.has("client_secret"), false);
});

test("la vuelta del servidor: codigo valido, sin codigo, state distinto o error", () => {
    assert.equal(Pkce.leerRespuesta("?code=abc&state=s1", "s1"), "abc");
    assert.equal(Pkce.leerRespuesta("", "s1"), null);
    assert.throws(() => Pkce.leerRespuesta("?code=abc&state=otro", "s1"), /state/);
    assert.throws(() => Pkce.leerRespuesta("?code=abc&state=s1", null), /state/);
    assert.throws(() => Pkce.leerRespuesta("?error=access_denied&error_description=no", "s1"), /access_denied: no/);
});

test("lee la carga util del JWT (con UTF-8) y detecta la caducidad", () => {
    const carga = { sub: "demo", scope: "pedidos.read", nombre: "Ana Núñez", exp: 2_000_000_000 };
    const base64Url = (texto) => Buffer.from(texto).toString("base64url");
    const token = base64Url('{"alg":"none"}') + "." + base64Url(JSON.stringify(carga)) + ".firma";

    assert.deepEqual(Pkce.leerJwt(token), carga);
    assert.equal(Pkce.leerJwt("no-es-un-jwt"), null);
    assert.equal(Pkce.leerJwt(undefined), null);
    assert.equal(Pkce.haCaducado(carga, Date.UTC(2026, 0, 1)), false);
    assert.equal(Pkce.haCaducado(carga, 2_000_000_000 * 1000), true);
    assert.equal(Pkce.haCaducado({ sub: "sin exp" }, 0), true);
});
