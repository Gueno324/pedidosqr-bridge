PEDIDOSQR BRIDGE OBSERVER

ESTA ENTREGA NO TOCA NADA DEL SISTEMA ACTUAL.
NO MODIFICA camarero.html, barra.html, Firebase ni la lógica de cobro.
ES UNA APP PARALELA EN MODO OBSERVADOR.

QUÉ HACE ESTA VERSIÓN:
- Lee ticketsPendientes del bar indicado
- Busca tickets con impreso = false
- Muestra el último ticket detectado
- Comprueba si la IP/puerto de impresora responde
- NO imprime
- NO marca impreso = true
- NO modifica Firebase

CONFIGURACIÓN INICIAL INCLUIDA:
- barId: pedidosqr-pruebas
- IP impresora: 192.168.1.228
- puerto: 9100

IMPORTANTE:
- Esta versión usa lectura REST sobre tu Realtime Database de pruebas
- Está pensada para VALIDAR detección de tickets sin tocar el sistema estable

CÓMO USARLA:
1. Abrir la carpeta del proyecto en Android Studio
2. Esperar a que descargue dependencias Gradle
3. Ejecutar en móvil Android o emulador con red
4. Pulsar GUARDAR
5. Pulsar INICIAR
6. Comprobar que detecta tickets pendientes

RIESGO ACTUAL:
- MUY BAJO
- No toca tu sistema web actual
- No escribe nada en Firebase
- No envía nada a impresora

SIGUIENTE FASE CUANDO ESTA QUEDE ESTABLE:
- activar impresión TCP/IP real
- después marcar impreso = true
- y solo al final eliminar popup del navegador
