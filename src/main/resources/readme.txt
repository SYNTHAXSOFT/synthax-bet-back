-- Crear el bot
En el buscador de Telegram escribe: @BotFather
Abre esa conversación (tiene una palomita azul de verificado)
Escribe /start
Luego escribe /newbot
Te pedirá:
Nombre del bot (el nombre visible): Synthax Bet
Username del bot (debe terminar en bot): synthaxbet_bot
BotFather te responde con un TOKEN que se ve así:
7412369850:AAFxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Guarda ese token — es la contraseña del bot, no la compartas

-- Agregar el bot como administrador de cada canal
Para que el bot pueda publicar en los canales, debe ser administrador:

Entra a tu canal Synthax Bet FREE
Toca el nombre del canal arriba → Administradores
Agregar administrador → busca @synthaxbet_bot
Dale permiso de "Publicar mensajes" → confirma
Repite en los 3 canales

-- Obtener el Chat ID de cada canal
El sistema necesita saber el ID numérico de cada canal para enviarle mensajes:

Envía cualquier mensaje en tu canal (ej: test)
Abre en el navegador esta URL (reemplaza el TOKEN):
https://api.telegram.org/bot<TOKEN>/getUpdates
Busca en la respuesta JSON el campo "chat" → "id" — será un número negativo como:
"chat": { "id": -1001234567890 }
Ese número es el Chat ID del canal — anótalo para cada uno
Si no aparece nada, envía un mensaje nuevo en el canal y vuelve a abrir la URL.


token:8708504524:AAFUWib_1Sc4jSkIuHbqTjJbN1UAEmDh67k