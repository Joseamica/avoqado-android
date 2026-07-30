# Guía operativa de vales por área

**Para:** cremería, panadería, cafetería, caja y responsables de turno  
**Versión:** 1.1 — 30 de julio de 2026
**Objetivo:** cobrar en una sola venta productos preparados en distintas áreas y entregarlos una sola vez.

## El flujo en una frase

Cada mostrador prepara el producto, imprime su propio vale y conserva el pedido. La caja escanea
todos los vales y los productos normales, cobra una sola vez y entrega un comprobante pagado.
Después, cada mostrador valida ese comprobante y entrega únicamente lo que le corresponde.

**Mostrador → vale → caja → un solo pago → comprobante pagado → entrega**

## Qué significa “área” en esta guía

En este flujo, **área** significa un departamento o mostrador que prepara productos: cremería,
panadería o cafetería. No significa una zona de mesas del restaurante. La pantalla **Mesas** no se
usa para emitir ni cobrar estos vales.

## Dónde se entra en Avoqado

| Acción | Pantalla |
|---|---|
| Preparar productos, capturar peso y emitir el vale | **Cobrar**. En una terminal configurada para un mostrador, el botón final dice **Emitir vale** en lugar de cobrar. |
| Escanear vales, agregar productos de tienda y cobrar | **Cobrar** en la terminal de caja. |
| Entregar productos después del pago | **Más → Entregas por área** en la terminal del mostrador. |
| Administrar mesas de restaurante | **Mesas**. No participa en este flujo. |

## Qué papel sirve para cada cosa

| Documento | Quién lo imprime | Para qué sirve |
|---|---|---|
| Vale de área | Cremería, panadería o cafetería | Llevar a caja los productos preparados por esa área. Todavía no acredita el pago. |
| Comprobante pagado | Caja | Acreditar el pago completo y recoger los productos retenidos. Incluye el código **ENTREGA POR ÁREA**. |

> Importante: el vale de área no sustituye el comprobante pagado. El producto retenido se entrega
> únicamente después de validar el pago.

## Responsabilidad de cada puesto

| Puesto | Responsabilidad |
|---|---|
| Mostrador | Capturar correctamente productos y peso, emitir el vale, conservar el pedido y entregarlo después de validar el pago. |
| Caja | Escanear todos los vales y productos normales, revisar el total, cobrar una sola vez y entregar el comprobante pagado. |
| Responsable de turno | Atender reimpresiones, códigos desconocidos, pagos con resultado pendiente e intentos de entrega duplicada. |

## Antes de abrir

### En cada mostrador

- Confirmar que la terminal muestra el nombre correcto del área.
- Confirmar que hay papel y que la impresora está encendida.
- Hacer una impresión de prueba si la impresora fue desconectada o cambiada.
- Si se venderán productos por peso, confirmar que la báscula marque cero antes de pesar.

### En caja

- Confirmar que la pistola o lector escriba códigos en la terminal.
- Confirmar que la impresora de recibos esté encendida y tenga papel.
- Confirmar que la caja tenga conexión a internet.
- Escanear un vale de prueba al iniciar el piloto o después de cambiar el lector.

## 1. Emitir un vale en cremería, panadería o cafetería

1. En la barra inferior, entra a **Cobrar**. No entres a **Mesas**.
2. Busca el producto en **Todos los productos**, **Shortcuts** o en la barra **Buscar** y tócalo.
3. Si el producto está configurado como **Se vende por peso**, Avoqado abre automáticamente el
   panel **Peso**. No captures el peso con el teclado de importes.
4. En el panel de peso:
   - coloca únicamente ese producto en la báscula;
   - espera a que la lectura se estabilice;
   - si aparece **Báscula**, espera el estado estable; Avoqado llenará el peso;
   - si no está conectada, pulsa **Capturar manualmente** y escribe el peso visible en
     **Peso (kg)**, por ejemplo `0.435`;
   - revisa el precio por kilogramo y el total mostrado;
   - pulsa **Agregar • $…**; el botón muestra el total calculado.
5. Repite el proceso si el vale tendrá otros productos del mismo mostrador.
6. Revisa productos, cantidades, peso y total antes de continuar.
7. Pulsa **Emitir vale**.
8. Espera a que termine la impresión.
9. Entrega el vale al cliente y conserva el producto preparado hasta que regrese con el
   comprobante pagado.

### Si el cliente pide algo más

Si el vale ya fue emitido, no se modifica. Captura el producto adicional y emite otro vale. La
caja puede juntar varios vales de la misma área en una sola venta.

### Si no sale el papel

Si Avoqado indica que el vale ya existe o muestra **Vale pendiente de impresión**, pulsa
**Reimprimir**. No vuelvas a crear el pedido: la reimpresión conserva el mismo código y evita un
doble cobro.

## 2. Consolidar y cobrar en caja

1. Abre una venta nueva.
2. Escanea el código de cada vale que entregue el cliente.
3. Espera el aviso **¡Vale agregado!** después de cada lectura.
4. Escanea o agrega los productos normales de tienda, por ejemplo papas, refrescos o productos
   empacados.
5. Revisa el carrito:
   - cada vale debe aparecer una sola vez;
   - los productos de vale están protegidos y no deben editarse en caja;
   - el total debe incluir los vales y los productos normales.
6. Si un vale es incorrecto, retíralo completo antes de cobrar y pide al área que emita uno nuevo.
7. Cobra la venta con el flujo normal de Avoqado.
8. Espera la confirmación de pago. No cobres nuevamente si la pantalla indica que el resultado
   está pendiente.
9. Entrega al cliente el comprobante pagado. Debe incluir **ENTREGA POR ÁREA** con código de barras
   y número legible.

### Si el lector no puede escanear un vale

Captura manualmente el código de 10 dígitos impreso debajo de las barras en **Código de vale o
producto**. No captures los productos del vale uno por uno en caja.

## 3. Entregar los productos retenidos

Cada área puede validar la entrega de dos formas. Ambas registran la entrega en Avoqado y evitan
que el mismo pedido se entregue dos veces.

### Opción A — escanear el comprobante pagado

1. En la terminal del área, abre **Más → Entregas por área**.
2. Pulsa **Escanear pagado**.
3. Escanea el código **ENTREGA POR ÁREA** del comprobante final.
4. Revisa los productos que Avoqado muestra para tu área.
5. Entrega únicamente esos productos al cliente.
6. Comprueba que Avoqado confirme la entrega.

### Opción B — revisar el papel y confirmar en la lista

1. En la terminal del área, abre **Más → Entregas por área**.
2. Localiza el vale pendiente por código, productos, importe u hora.
3. Revisa visualmente el comprobante pagado del cliente.
4. Entrega los productos.
5. Pulsa **Revisé el papel y entregué**.
6. Comprueba que aparezca **Entrega registrada**.

### Regla de seguridad

Una terminal sólo muestra y entrega los productos de su propia área. Si Avoqado informa que el
vale ya fue entregado, no vuelvas a entregar el producto; llama al responsable de turno.

## 4. Uso de las básculas

### Configuración inicial de un producto por peso

Esta configuración la realiza el encargado una sola vez desde el Dashboard:

1. Abre **Menú → Productos**.
2. Crea el producto o abre uno existente, por ejemplo Jamón.
3. Activa **Se vende por peso**.
4. Captura el **Precio por kg** y guarda.
5. En la terminal, pulsa el botón circular de actualizar que aparece junto a **Buscar**.

Después de configurarlo, tocar ese producto desde **Cobrar** abrirá directamente el panel
**Peso**. El teclado grande que aparece en la pestaña **Teclado** sirve para importes libres; no
debe usarse para pesar.

### Cremería — Rhino BAR-8RS

1. Entra a **Cobrar** y toca un producto configurado como **Se vende por peso**.
2. Coloca el producto y espera a que la tarjeta de báscula muestre una lectura estable.
3. Revisa cero/tara, kilogramos y total.
4. Pulsa **Agregar**. Si el cable o la lectura fallan, usa **Capturar manualmente**.

### CEDIS — Justa LP7516

La primera pantalla habilitada es **Inventario → Conteos**; la báscula no crea vales ni ventas.

1. Entra a **Inventario** y abre **Conteos**.
2. Inicia un conteo completo o cíclico.
3. Selecciona un insumo cuya unidad sea kilogramo o gramo.
4. Coloca el producto en la Justa y espera una lectura estable.
5. Revisa el valor y pulsa **Usar este peso**. Avoqado llena el campo del conteo, pero no lo guarda
   sin tu confirmación.
6. Continúa con **Siguiente artículo** y termina con **Revisar conteo**.

Para artículos en piezas, litros u otras unidades se usa el teclado normal. La recepción de órdenes
de compra permanece manual en esta etapa.

En ambos lugares el operador siempre revisa cero o tara correcta, lectura estable y unidad. Si la
lectura automática falla, se captura manualmente el peso visible; caja, vales e inventario no se
detienen.

## 5. Qué hacer cuando algo no sale como se esperaba

| Situación | Acción correcta |
|---|---|
| El vale existe, pero no se imprimió | Usar **Reimprimir**. No emitir otro vale. |
| El código no se escanea | Escribir el código de 10 dígitos. Si tampoco funciona, avisar al responsable. |
| El vale aparece como ya agregado | No volver a capturar sus productos. Revisar el carrito actual. |
| El vale aparece como ya pagado | No cobrarlo otra vez. Usarlo en **Entregas por área**. |
| El vale tiene un producto, peso o precio incorrecto | Retirar el vale completo antes del pago y pedir al área uno nuevo. |
| Se perdió un vale antes del pago | El área reimprime el mismo vale. |
| Se perdió el comprobante después del pago | Caja reimprime el comprobante final; debe conservar el mismo código de entrega. |
| El pago quedó pendiente | No intentar otro cobro hasta consultar el resultado. |
| No hay internet | Conservar el producto y reintentar al volver la conexión. Los vales no se emiten, cobran ni entregan sin conexión. |
| Avoqado dice que ya fue entregado | No entregar nuevamente. Escalar al responsable de turno. |
| La báscula no comunica con Avoqado | Capturar manualmente el peso visible y continuar. |

## Reglas que evitan errores

- Un vale pertenece a una sola área.
- Un cliente puede llevar varios vales, incluso de la misma área.
- Todos los vales y productos normales se cobran en una sola venta.
- Caja no cambia productos, peso ni precio de un vale impreso.
- Reimprimir no significa volver a emitir.
- El área conserva el producto hasta validar el comprobante pagado.
- Cada área entrega sólo sus propios productos.
- Toda entrega se confirma en Avoqado, aunque también se revise el papel.
- Un pago pendiente nunca se repite sin comprobar primero su resultado.
- Si no hay red, no se improvisa un cobro separado para los productos de un vale.

## Ejemplo completo

Un cliente compra `250 g de jamón`, `un café`, `dos panes` y `una bolsa de papas`.

1. Cremería pesa el jamón, captura `0.250 kg`, emite su vale y conserva el jamón.
2. Cafetería prepara el café, emite su vale y lo conserva.
3. Panadería prepara los panes, emite su vale y los conserva.
4. El cliente lleva los tres vales y la bolsa de papas a caja.
5. Caja escanea los tres vales y después las papas.
6. Avoqado forma una sola venta y caja cobra un solo total.
7. Caja entrega el comprobante pagado con el código **ENTREGA POR ÁREA**.
8. El cliente vuelve a cada mostrador.
9. Cada área escanea el comprobante o revisa el papel, confirma la entrega y entrega únicamente su
   producto.

## Práctica recomendada antes de atender clientes

Realicen una compra completa de capacitación con un producto de cada área y un producto normal de
tienda:

- verificar que se impriman tres vales distintos;
- comprobar que la caja agregue cada vale una sola vez;
- cobrar una sola venta;
- confirmar que el comprobante final muestre el código de entrega;
- entregar un área por escaneo y otra mediante revisión del papel;
- intentar validar de nuevo una entrega para comprobar que Avoqado no permita duplicarla.

## Lista rápida de cierre

- No quedaron productos físicos retenidos sin un vale identificable.
- No quedaron pagos con resultado pendiente sin seguimiento.
- La lista **Entregas por área** no contiene pedidos que ya se entregaron físicamente.
- Las impresoras tienen papel para el siguiente turno.
- Cualquier falla de báscula, lector o impresora quedó informada al responsable.

**Soporte:** al reportar un problema, envía el nombre del área, la hora aproximada, el código del
vale o de entrega y una foto completa de la pantalla o del papel. Nunca envíes datos de tarjeta.
