# Guía de instalación — Hub LAN offline

**Para quien instala Avoqado en un local.** Sigue esto ANTES de irte del sitio.
Si te saltas la sección 2, el hub parecerá funcionar y no funcionará: falla en
silencio y sólo lo notarás el día que se caiga el internet, que es justo el peor
momento para enterarse.

---

## 1. Qué es esto (30 segundos)

Sin internet, cada POS queda aislado y **dos meseros pueden abrir la misma
mesa**. El hub LAN hace que los POS se hablen **entre ellos por el WiFi del
local**, se repartan las mesas y ese choque no ocurra.

- Es **sólo red local**. No sale a internet, no necesita puertos abiertos hacia
  afuera, no hay nada que configurar en el módem del ISP.
- Funciona **precisamente cuando no hay internet**.
- Si el hub no está disponible, el POS **sigue vendiendo** en modo isla (como
  siempre). Nunca bloquea un cobro.

Requiere plan **PREMIUM** (código de feature `OFFLINE_LAN_HUB`).

---

## 2. 🔴 Lo que SÍ tienes que revisar en la red del local

Cuatro ajustes. Ninguno cuesta dinero; todos están en el router o los access
points. **El primero es el que rompe todo y viene encendido por defecto.**

### 2.1 Apagar el aislamiento de clientes ← EL IMPORTANTE

Busca en el router/AP una opción llamada:

- **AP Isolation** / **Client Isolation** / **Wireless Isolation**
- **Aislamiento de clientes** / **Aislamiento inalámbrico**
- En UniFi: *Settings → WiFi → (red) → Advanced →* **Client Device Isolation**
- En redes de invitados suele llamarse **Guest Policy** o **Guest Mode**

**Tiene que estar APAGADO en la red donde viven los POS.**

Esto bloquea que dos dispositivos del mismo WiFi se hablen. Si está encendido,
cada POS se queda solo, el hub cae a modo isla y no previene nada — sin ningún
mensaje de error.

### 2.2 Todos los POS en la MISMA red

Misma SSID y mismo rango de IP (por ejemplo, todos en `192.168.1.x`).

El descubrimiento usa mDNS, que **no cruza routers ni VLANs** por diseño. Si la
caja está en una red y los meseros en otra, no se ven aunque ambas tengan
internet.

> ¿La política del cliente exige separarlas? Entonces hay que activar el
> *mDNS repeater* / *Bonjour gateway* en los APs (existe en UniFi, Aruba y
> Cisco). Si no sabes hacerlo, es preferible dejarlos en la misma red.

### 2.3 NUNCA poner el POS en la red de invitados

Ahí el aislamiento suele ser obligatorio y no se puede desactivar.

### 2.4 No filtrar multicast (sólo APs empresariales)

Algunos APs descartan tráfico multicast para ahorrar ancho de banda. Revisa que
esté **desactivado**:

- UniFi: **Block LAN to WLAN Multicast and Broadcast** → apagado
- Otros: opciones tipo *Multicast Filtering* o *IGMP Snooping* agresivo

---

## 3. Recomendado (no obligatorio)

- **Un POS con cable Ethernet** (dock o adaptador). El sistema lo elige como
  árbitro automáticamente, y al no moverse por el salón no pierde señal.
- **Reserva de DHCP** para las impresoras, para que su IP no cambie.

---

## 4. Cómo comprobar que quedó bien, en el sitio

Con **dos POS encendidos** en la red del local:

1. Desconecta el internet del local (desenchufa el cable WAN del módem).
   El WiFi debe seguir encendido — esto simula un apagón de internet real.
2. En ambos POS aparece el banner naranja *"Sin conexión — las ventas se
   guardan en el dispositivo"*.
3. En el **POS A**, abre una mesa cualquiera (por ejemplo M5).
4. En el **POS B**, intenta abrir **esa misma mesa**.

**✅ Correcto:** el POS B muestra en rojo
*"{nombre del mesero} está atendiendo esta mesa en otro dispositivo"* y no la
abre.

**❌ Mal:** el POS B abre la mesa sin protestar → el hub no se está viendo.
Vuelve al paso 2.1 (casi siempre es el aislamiento de clientes).

5. Vuelve a conectar el internet y confirma que las ventas suben solas.

---

## 5. Prueba de impresión (hazla siempre)

Con el internet del local **desconectado**:

1. Abre una mesa, agrega un producto y pulsa **Enviar**.
2. **La comanda tiene que salir en la impresora de cocina.**

Si no sale, casi seguro es que **ese dispositivo nunca se conectó con internet
después de instalarse**: la configuración de impresoras se descarga una vez y
queda guardada, pero si nunca la descargó no tiene a dónde imprimir.

> **Solución:** conecta el internet, abre la app, envía UNA ronda de prueba
> (esto guarda la configuración en el dispositivo) y repite la prueba offline.
>
> Haz esto en **cada POS nuevo** antes de dejar el local.

---

## 6. Preguntas que te van a hacer

**¿Necesito internet para que funcione el hub?**
No. Al revés: es para cuando no hay.

**¿Hay que abrir puertos en el router?**
No. Todo el tráfico es dentro de la red local.

**¿Y si se apaga el POS que hace de árbitro?**
Los demás eligen otro solos. Las mesas que tenía tomadas se liberan a los
30 segundos y se pueden volver a abrir.

**¿Se pueden perder ventas si el hub falla?**
No. Si el hub no está, cada POS trabaja como isla y guarda todo en el
dispositivo; al volver el internet sube solo. El hub sólo evita que dos meseros
se pisen — nunca impide cobrar.

**¿Y si dos meseros abrieron la misma mesa antes de instalar el hub?**
El sistema lo detecta al reconectar y lo deja en la pantalla de *"operaciones
que necesitan revisión"* con el motivo. Nada se pierde en silencio.

---

## 7. Checklist para imprimir y llevar

```
[ ] Plan del venue = PREMIUM (feature OFFLINE_LAN_HUB)
[ ] Aislamiento de clientes APAGADO en la red del POS   ← el que rompe todo
[ ] Todos los POS en la misma SSID y mismo rango de IP
[ ] Los POS NO están en la red de invitados
[ ] Multicast NO filtrado en los APs
[ ] Cada POS abrió la app CON internet al menos una vez (guarda impresoras)
[ ] Prueba con internet desconectado: la 2a tablet NO puede abrir la mesa
[ ] Prueba con internet desconectado: la comanda SÍ sale impresa
[ ] Internet reconectado: las ventas suben solas
```
