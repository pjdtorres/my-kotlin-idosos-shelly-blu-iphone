# MQTT – Telemóvel → EMQX → Node-RED

## Ficheiros
- `app/src/main/java/com/example/esp32_eletronica117/MqttEnvio.kt` – todo o código MQTT da app
- `mqtt/nodered-telemovel-flow.json` – flow para importar no Node-RED

## Broker
- EMQX no Hetzner: `tcp://89.167.29.191:1883` (sem TLS)
- Utilizador/password: em `MqttEnvio.kt` (`UTILIZADOR`, `PASSWORD`). Vazio = anónimo.

## Tópicos
| Tópico | Conteúdo | QoS | Quando |
|---|---|---|---|
| `esp32-udemy/telemovel/dados` | `{"x":0.01,"y":-0.02,"z":0.00,"inclinacao":3.2,"horizontal":true,"ts":1760366400000}` | 0 | 1×/s e logo que a posição muda |
| `esp32-udemy/telemovel/queda` | `{"queda":true,"ts":...}` | 1 | quando é detetada uma queda |
| `esp32-udemy/telemovel/estado` | `online` / `offline` (retido, last will) | 1 | ao ligar / desligar |
| `esp32/acelerometro` | `{"lx":0.012,"ly":-0.031,"lz":0.004,"roll":1.20,"pitch":-0.85,"yaw":0,"yaw_ok":0,"sample_utc":"2026-10-13T15:23:00.100Z"}` | 0 | 10×/s – página "Gráficos" do FastAPI (17-mongodb-bootstrap5-websockets) |

| `esp32/acelerometro/config/set` | `100` (recebido: intervalo em ms, 10–1000) | 1 | a app subscreve; vem do botão "Aplicar intervalo" da página |
| `esp32/acelerometro/config` | `{"period_ms":100,"sensor_on":true}` | 1 | confirmação, ao ligar e a cada alteração |

Nota: `esp32/acelerometro` é o mesmo tópico do BNO085 da T-SIM7000G; se a placa estiver ligada, os dados misturam-se no gráfico e no CSV.

x, y, z em m/s² sem gravidade; inclinação em graus (0° = deitado face para cima, 90° = em pé).

## Alterações feitas na app
1. `gradle/libs.versions.toml` – biblioteca `org.eclipse.paho.client.mqttv3:1.2.5`
2. `app/build.gradle.kts` – `implementation(libs.paho.mqtt)`
3. `AndroidManifest.xml` – permissões `INTERNET` e `ACCESS_NETWORK_STATE`
4. `MainActivity.kt` – `mqtt.ligar()` no onResume, `mqtt.desligar()` no onPause,
   `mqtt.enviarDados(...)` a cada leitura, `mqtt.enviarQueda()` na queda.
   O estado da ligação aparece na barra de título.

## Node-RED
1. Menu ☰ → Import → selecionar `nodered-telemovel-flow.json` → Import → Deploy.
2. Se o EMQX exigir credenciais: abrir o nó broker "EMQX myiot (telemóvel)" → Security.
3. Ver as mensagens no separador Debug (à direita).

## Testar sem Node-RED
`mosquitto_sub -h 89.167.29.191 -t "esp32-udemy/telemovel/#" -v`


## Nome da pessoa (app Kotlin)
- No topo da app há o campo **Nome da pessoa** + botão **Guardar** (fica guardado no telemóvel).
- Esse nome é o `label` do marcador no mapa (`fastapi/map/positions`) e vai também no JSON de `dados` e `queda` (campo `"nome"`).
- `device_id` = `telemovel-` + 6 últimos caracteres do ANDROID_ID: é diferente em cada telemóvel e não muda quando se muda o nome.
- Os tópicos `esp32-udemy/telemovel/...` não mudaram: para saber de quem é a mensagem, ler `nome` / `device_id` no JSON.


## Botão Shelly BLU Button1 (Bluetooth)
- Código: `ShellyBlu.kt` (escuta os anúncios BTHome v2, UUID 0xFCD2, sem emparelhar) + `MonitorService.tratarBotao()`.
- **Associar**: na app, carregar em **Associar botão** e, nos 30 s seguintes, carregar 1× no botão Shelly.
  O MAC fica guardado; podem associar-se vários botões (a embalagem traz 2). **Esquecer** apaga-os todos.
  Botões não associados (dos vizinhos) são ignorados.
- A **encriptação** do botão tem de estar **desligada** (é assim que vem de fábrica; na app Shelly não ligar "Encryption").
- Ações (constantes no topo do `MonitorService`):

| Carregão | SMS | MQTT |
|---|---|---|
| 1× / longo / manter | `PEDIDO DE AJUDA: nome (às hh:mm:ss)` + morada (no máx. 1 a cada 20 s) | sim |
| 2× | `Está tudo bem! (cancelado no botão)` | sim |
| 3× | — | sim |

- Tópico `esp32-udemy/telemovel/botao` (QoS 1):
  `{"nome":"D. Maria","device_id":"telemovel-3fa9c1","botao":"7C:C6:B6:61:A2:3F","evento":"carregar","bateria_botao":100,"rssi":-62,"ts":...}`
  `evento`: `carregar`, `duplo`, `triplo`, `longo`, `manter`.
- Autorizações: Android 12+ pede **Dispositivos próximos** (BLUETOOTH_SCAN) ao carregar em Associar; Android ≤ 11 usa a de Localização.
- O Bluetooth do telemóvel tem de estar ligado; o scan continua com o ecrã desligado (é feito no `MonitorService`, com filtro).
- Alcance típico: ~10–30 m dentro de casa (o `rssi` ajuda a perceber se está longe: -90 dBm já é o limite).
