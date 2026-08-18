#!/usr/bin/env node
/**
 * Regenera el fixture `PermisosRealesDelServer` desde avoqado-server.
 *
 *   node scripts/regenerar-permisos-reales.mjs           # reescribe fixture + snapshot
 *   node scripts/regenerar-permisos-reales.mjs --check   # no escribe; exit 1 si algo cambió
 *
 * 🔴 POR QUÉ EXISTE ESTE SCRIPT Y NO UNA RECETA EN UN COMENTARIO: el fixture ya
 * mintió una vez. Era una lista corta escrita a mano, titulada "los permisos
 * REALES", que omitía justo las dependencias IMPLÍCITAS que el server expande
 * antes de mandar la lista (`orders:update` arrastra `inventory:read`). Un
 * fixture recortado deja pasar en verde un gate que en el aparato hace lo
 * contrario. Y el intento anterior de arreglarlo dejó una receta que apuntaba a
 * un script que nunca se commiteó, o sea a nada.
 *
 * Escribe TRES artefactos desde la MISMA corrida, y los tests los comparan:
 *   - app/src/test/java/com/avoqado/pos/core/domain/PermisosRealesDelServer.kt
 *   - app/src/test/java/com/avoqado/pos/core/domain/PermisosDeRutasDelServer.kt
 *   - app/src/test/resources/permisos-efectivos-del-server.json
 * Si alguien edita un Kotlin a mano, el test truena. Para detectar que el SERVER
 * se movió y nadie regeneró, corre este script con `--check`.
 */
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ANDROID = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SERVER = resolve(ANDROID, '..', 'avoqado-server')
const KT = resolve(ANDROID, 'app/src/test/java/com/avoqado/pos/core/domain/PermisosRealesDelServer.kt')
const KT_RUTAS = resolve(ANDROID, 'app/src/test/java/com/avoqado/pos/core/domain/PermisosDeRutasDelServer.kt')
const JSON_SNAPSHOT = resolve(ANDROID, 'app/src/test/resources/permisos-efectivos-del-server.json')

/** De menos a más autoridad: se lee mejor así que en el orden del enum del server. */
const ORDEN = ['VIEWER', 'HOST', 'KITCHEN', 'WAITER', 'CASHIER', 'MANAGER', 'ADMIN', 'OWNER', 'SUPERADMIN']

const check = process.argv.includes('--check')

if (!existsSync(SERVER)) {
  console.error(`✖ No encuentro avoqado-server en ${SERVER}.`)
  console.error('  Este fixture SÓLO se puede derivar del server; no lo escribas a mano.')
  process.exit(2)
}

const raw = execFileSync('npx', ['tsx', 'scripts/dump-effective-role-permissions.ts'], {
  cwd: SERVER,
  encoding: 'utf8',
  maxBuffer: 32 * 1024 * 1024,
})
const dump = JSON.parse(raw)

const faltantes = ORDEN.filter(r => !dump.roles[r])
if (faltantes.length) {
  console.error(`✖ El server ya no manda estos roles: ${faltantes.join(', ')}. Actualiza ORDEN en este script.`)
  process.exit(2)
}
const nuevos = Object.keys(dump.roles).filter(r => !ORDEN.includes(r))
if (nuevos.length) {
  console.error(`✖ El server tiene roles que este script no conoce: ${nuevos.join(', ')}. Agrégalos a ORDEN.`)
  process.exit(2)
}

const kotlinList = (nombre, permisos, doc) =>
  `    ${doc}\n    val ${nombre} = listOf(\n${permisos.map(p => `        "${p}",`).join('\n')}\n    )\n`

const notaDeRol = (rol, { declared, effective, implicit }) => {
  const impl = implicit.length ? ` + ${implicit.length} implícito${implicit.length === 1 ? '' : 's'}: ${implicit.join(', ')}` : ''
  return `/** ${rol} — ${effective.length} permisos efectivos (${declared.length} declarado${declared.length === 1 ? '' : 's'}${impl}). */`
}

const cuerpo = ORDEN.map(rol => kotlinList(rol, dump.roles[rol].effective, notaDeRol(rol, dump.roles[rol]))).join('\n')

const implicitos = ORDEN.map(
  rol =>
    `        "${rol}" to listOf(\n${dump.roles[rol].implicit.map(p => `            "${p}",`).join('\n')}${dump.roles[rol].implicit.length ? '\n' : ''}        ),`,
).join('\n')

const kt = `package com.avoqado.pos.core.domain

/**
 * Los permisos EFECTIVOS que el server manda de verdad a esta app, por rol.
 *
 * 🔴 ARCHIVO GENERADO — NO LO EDITES A MANO. Se regenera con:
 * \`\`\`
 * node scripts/regenerar-permisos-reales.mjs          # reescribe este archivo
 * node scripts/regenerar-permisos-reales.mjs --check  # exit 1 si el server se movió
 * \`\`\`
 * El script llama \`getEffectiveRolePermissions(rol, null)\`
 * (\`avoqado-server/src/lib/permissions.ts\`) — exactamente la función con la que
 * \`auth.mobile.service.ts\` llena \`venue.permissions\` en el login, que es el campo
 * que esta app guarda en \`SecureStorage.venuePermissions\`.
 *
 * 🔴 POR QUÉ TIENE QUE SER LA LISTA COMPLETA: el server EXPANDE dependencias
 * implícitas antes de mandarla. \`orders:update\` arrastra \`inventory:read\`, así que
 * el CAJERO, el MESERO y la COCINA reciben \`inventory:read\` aunque nadie se los
 * concedió a mano. Un fixture recortado "con los permisos importantes" omite justo
 * esas entradas y deja pasar en verde un gate que en el aparato hace lo contrario.
 * Ya pasó: este archivo fue una vez una lista de 9 nombres titulada "los permisos
 * REALES".
 *
 * 🔴 QUÉ *NO* ES: no es la autoridad. La autoridad es la lista que llegó en el
 * login, y por eso los gates leen \`SecureStorage.venuePermissions\` y no un rol. Un
 * venue con Permission Sets (\`VenueRolePermission\`) manda otra cosa; aquí sale la
 * matriz por default (\`customPermissions = null\`), que es la de la mayoría.
 *
 * Derivado de ${dump.generatedFrom.repo}@${dump.generatedFrom.commit}.
 */
object PermisosRealesDelServer {

${cuerpo}
    /** Los 9 roles por nombre EXACTO del enum \`StaffRole\` del server. */
    val PorRol: Map<String, List<String>> = mapOf(
${ORDEN.map(r => `        "${r}" to ${r},`).join('\n')}
    )

    /**
     * Lo que NADIE concedió a mano: \`efectivos − declarados\`.
     *
     * Está aquí para que un test pueda afirmar "esto llega por dependencia, no
     * porque alguien se lo diera" sin que nadie tenga que adivinarlo leyendo
     * \`PERMISSION_DEPENDENCIES\`. Es la mitad del fixture que la versión escrita a
     * mano se comió.
     */
    val ImplicitosPorRol: Map<String, List<String>> = mapOf(
${implicitos}
    )
}
`

const ARCHIVO_A_VAL = { 'mobile.routes.ts': 'MOBILE', 'tpv.routes.ts': 'TPV', 'pos-sync.routes.ts': 'POS_SYNC' }

const rutasVals = Object.entries(ARCHIVO_A_VAL)
  .map(([archivo, val]) => {
    const permisos = dump.routePermissions[archivo] ?? []
    const doc = `    /** Los ${permisos.length} permisos que un \`checkPermission(...)\` de \`${archivo}\` puede rechazar. */`
    const cuerpoVal = permisos.length
      ? `listOf(\n${permisos.map(p => `        "${p}",`).join('\n')}\n    )`
      : 'emptyList()'
    return `${doc}\n    val ${val}: List<String> = ${cuerpoVal}\n`
  })
  .join('\n')

const ktRutas = `package com.avoqado.pos.core.domain

/**
 * Todo permiso que una ruta del server puede rechazarle a un POS.
 *
 * 🔴 ARCHIVO GENERADO — NO LO EDITES A MANO. Sale del mismo
 * \`scripts/regenerar-permisos-reales.mjs\` que \`PermisosRealesDelServer\`, leyendo
 * los \`checkPermission(...)\` de \`mobile.routes.ts\`, \`tpv.routes.ts\` y
 * \`pos-sync.routes.ts\`.
 *
 * 🔴 PARA QUÉ SIRVE: para que la cobertura de \`PermissionLabels\` se verifique
 * sola. Antes esa lista vivía escrita a mano dentro de un test, con un comentario
 * que ya admitía el problema ("si el server agrega un checkPermission nuevo, este
 * test NO se entera solo"). Y no se enteró: el server estrenó \`estimates:create\`
 * y \`orders:cancel-unpaid\`, el modal empezó a enseñar el código pelón —que es
 * justo el síntoma que el founder reportó en vivo— y la suite siguió verde.
 *
 * 🔴 LO QUE **NO** CUBRE, a propósito: los permisos que eximen de la propiedad de
 * mesa (\`tables:manage-all\`, \`tables:pay-any\`) no pasan por \`checkPermission\`
 * sino por \`checkTableOwnership\`, y su 403 trae \`code: TABLE_OWNED_BY_OTHER\` sin
 * nombre de permiso. No salen aquí porque el modal nunca los pide; sus etiquetas
 * existen para el resto de la app, no para ese modal.
 *
 * Derivado de ${dump.generatedFrom.repo}@${dump.generatedFrom.commit}.
 */
object PermisosDeRutasDelServer {

${rutasVals}
    /** La unión, sin repetidos — lo que la app puede toparse por cualquier puerta. */
    val TODOS: List<String> = (MOBILE + TPV + POS_SYNC).distinct().sorted()
}
`

const snapshot = JSON.stringify(dump, null, 2) + '\n'

if (check) {
  const iguales = [
    [KT, kt],
    [KT_RUTAS, ktRutas],
    [JSON_SNAPSHOT, snapshot],
  ].filter(([ruta, esperado]) => !existsSync(ruta) || readFileSync(ruta, 'utf8') !== esperado)
  if (iguales.length) {
    console.error('✖ El fixture NO cuadra con el server. Desactualizado:')
    iguales.forEach(([ruta]) => console.error(`    ${ruta}`))
    console.error('  Corre: node scripts/regenerar-permisos-reales.mjs')
    process.exit(1)
  }
  console.log(`✓ El fixture cuadra con ${dump.generatedFrom.repo}@${dump.generatedFrom.commit}.`)
  process.exit(0)
}

writeFileSync(KT, kt)
writeFileSync(KT_RUTAS, ktRutas)
writeFileSync(JSON_SNAPSHOT, snapshot)
console.log(`✓ Regenerado desde ${dump.generatedFrom.repo}@${dump.generatedFrom.commit}:`)
ORDEN.forEach(r => console.log(`    ${r.padEnd(11)} ${String(dump.roles[r].effective.length).padStart(3)} permisos`))
console.log(`    rutas       ${String(Object.values(dump.routePermissions).flat().filter((v, i, a) => a.indexOf(v) === i).length).padStart(3)} permisos rechazables`)
