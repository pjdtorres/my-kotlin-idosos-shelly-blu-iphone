import SwiftUI
import CoreLocation
import CoreBluetooth
import CoreMotion
import CocoaMQTT

final class MonitorController: NSObject, ObservableObject, CLLocationManagerDelegate, CBCentralManagerDelegate {
    static let shared = MonitorController()
    private static let activeKey = "monitorizacaoAtiva"
    private static let nameKey = "nome"
    private static let intervalKey = "intervaloGraficoMs"
    private static let mqttOffKey = "mqttDesligado"
    private static let refKey = "referenciaGravidade"

    // ---- Estado mostrado no ecrã ----
    @Published private(set) var active = false
    @Published private(set) var mqttOn = false
    @Published private(set) var mqttStatus = "MQTT desligado"
    @Published private(set) var gpsStatus = "GPS desligado"
    @Published private(set) var gpsDetail = ""
    @Published private(set) var address = ""
    @Published private(set) var motionStatus = "Movimento desligado"
    @Published private(set) var bluetoothStatus = "Bluetooth desligado"
    @Published private(set) var shellyText = "Botão Shelly: sem eventos"
    @Published private(set) var lastEvent = "Sem eventos"
    @Published private(set) var batteryText = ""
    @Published private(set) var axisX = 0.0
    @Published private(set) var axisY = 0.0
    @Published private(set) var axisZ = 0.0
    @Published private(set) var inclinationNow = 0.0
    @Published private(set) var inclinationFromRef: Double? = nil
    @Published private(set) var rotationZ: Double? = nil
    @Published private(set) var intervalMs = 100

    // ---- Sensores e ligações ----
    private let location = CLLocationManager()
    private let motion = CMMotionManager()
    private let geocoder = CLGeocoder()
    private var bluetooth: CBCentralManager?
    private var mqtt: CocoaMQTT?
    private var timer: Timer?
    private var graphTimer: Timer?
    private var uiTimer: Timer?
    private var connected = false
    private var name = "Telemóvel"
    private var lastLocation: CLLocation?
    private var lastMotion: (Double, Double, Double, Double)?
    private var rawYaw: Double?
    private var referenceYaw: Double?
    private var rotationSpeed = 0.0
    private var gravity = [Double](repeating: 0, count: 3)
    private var firstSample = true
    private var fall = FallRule()
    private var associateUntil: Date?
    private var lastPackets: [String: (Int, Date)] = [:]
    private let bthome = CBUUID(string: "FCD2")

    // ---- Regras iguais à versão Android ----
    private let inclinationAlert = 40.0          // > 40° (desde a referência) durante 1 s -> ALERTA
    private let resendGpsEvery = 10              // reenvia a posição a cada 10 s
    private var aboveSince: Date?
    private var belowSince: Date?
    private var inAlert = false
    private var lastFall: Date?
    private var secondsSinceGps = 0
    private var lastGpsSent = Date.distantPast
    private var startedAt = Date()

    // Referência ("Definir referência" ou ZERO da página): gravidade, roll e pitch nesse momento
    private var refGravity: [Double]?
    private var refRoll = 0.0
    private var refPitch = 0.0

    // Morada
    private var lastGeocoded: CLLocation?
    private var lastGeocodeTime = Date.distantPast

    private let clock: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    override init() {
        super.init()
        let saved = UserDefaults.standard.integer(forKey: Self.intervalKey)
        if (10...1000).contains(saved) { intervalMs = saved }
        if let r = UserDefaults.standard.array(forKey: Self.refKey) as? [Double], r.count == 5 {
            refGravity = Array(r[0..<3]); refRoll = r[3]; refPitch = r[4]
        }
    }

    private var deviceID: String {
        let key = "iphoneDeviceID"
        if let saved = UserDefaults.standard.string(forKey: key) { return saved }
        let value = "telemovel-iphone-" + UUID().uuidString.prefix(8).lowercased()
        UserDefaults.standard.set(value, forKey: key)
        return value
    }

    // MARK: - Iniciar / parar

    /// Retoma a monitorização se estava ativa quando a app foi fechada pelo iOS.
    func resumeIfNeeded() {
        guard UserDefaults.standard.bool(forKey: Self.activeKey) else { return }
        start(name: UserDefaults.standard.string(forKey: Self.nameKey) ?? "Telemóvel")
    }

    func start(name: String) {
        guard !active else { return }
        self.name = name.isEmpty ? "Telemóvel" : name
        active = true
        startedAt = Date()
        UserDefaults.standard.set(true, forKey: Self.activeKey)
        UIDevice.current.isBatteryMonitoringEnabled = true
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBest
        // Mantém a app a correr com o ecrã bloqueado: enquanto o GPS estiver ativo em
        // segundo plano, o acelerómetro, o MQTT e os temporizadores continuam a funcionar.
        location.allowsBackgroundLocationUpdates = true
        location.pausesLocationUpdatesAutomatically = false
        location.showsBackgroundLocationIndicator = true
        location.requestAlwaysAuthorization()
        startLocationIfAllowed()
        bluetooth = CBCentralManager(delegate: self, queue: nil)
        startMotion()
        if !UserDefaults.standard.bool(forKey: Self.mqttOffKey) { connectMQTT() }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.everySecond()
        }
        uiTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.refreshScreen()
        }
        restartGraphTimer()
    }

    func stop() {
        guard active else { return }
        active = false
        UserDefaults.standard.set(false, forKey: Self.activeKey)
        timer?.invalidate(); timer = nil
        uiTimer?.invalidate(); uiTimer = nil
        graphTimer?.invalidate(); graphTimer = nil
        location.stopUpdatingLocation()
        location.stopMonitoringSignificantLocationChanges()
        location.allowsBackgroundLocationUpdates = false
        motion.stopAccelerometerUpdates()
        motion.stopDeviceMotionUpdates()
        rawYaw = nil; referenceYaw = nil; rotationZ = nil; rotationSpeed = 0
        bluetooth?.stopScan()
        disconnectMQTT()
        gpsStatus = "GPS desligado"
        motionStatus = "Movimento desligado"
        bluetoothStatus = "Bluetooth desligado"
    }

    // MARK: - Botões do ecrã

    /// Novo nome: passa a ser o label no mapa (reenvia já a posição, como no Android).
    func setName(_ newName: String) {
        name = newName.isEmpty ? "Telemóvel" : newName
        UserDefaults.standard.set(name, forKey: Self.nameKey)
        if let position = lastLocation { sendLocation(position) }
    }

    /// Intervalo de envio para a página "Gráficos" (10 a 1000 ms). Devolve false se inválido.
    @discardableResult
    func applyInterval(_ ms: Int) -> Bool {
        guard (10...1000).contains(ms) else { return false }
        intervalMs = ms
        UserDefaults.standard.set(ms, forKey: Self.intervalKey)
        restartGraphTimer()
        publishConfig()
        return true
    }

    /// Botão "Ligar MQTT" / "Desligar MQTT".
    func toggleMQTT() {
        guard active else { return }
        if mqttOn {
            UserDefaults.standard.set(true, forKey: Self.mqttOffKey)
            disconnectMQTT()
        } else {
            UserDefaults.standard.set(false, forKey: Self.mqttOffKey)
            connectMQTT()
        }
    }

    /// "Definir referência": a posição atual passa a ser a posição de repouso.
    func setReference() {
        guard !firstSample else { return }
        refGravity = gravity
        let (roll, pitch) = absoluteRollPitch()
        refRoll = roll; refPitch = pitch
        referenceYaw = rawYaw
        UserDefaults.standard.set(gravity + [roll, pitch], forKey: Self.refKey)
        aboveSince = nil; belowSince = nil
        lastEvent = "Referência definida às \(clock.string(from: Date()))"
        sendGraph()   // a página recebe já a leitura com os zeros
    }

    func associateButton() {
        guard active else { return }
        associateUntil = Date().addingTimeInterval(30)
        bluetoothStatus = "Carregue no Shelly nos próximos 30 s"
    }

    func forgetButtons() {
        UserDefaults.standard.removeObject(forKey: "shellyIDs")
        bluetoothStatus = "Botões esquecidos"
    }

    func helpMessage(name: String) -> String {
        var message = "PEDIDO DE AJUDA: \(name)"
        if !address.isEmpty { message += "\n\(address)" }
        if let coordinate = lastLocation?.coordinate {
            message += "\nhttps://maps.google.com/?q=\(coordinate.latitude),\(coordinate.longitude)"
        }
        return message
    }

    // MARK: - MQTT

    private func connectMQTT() {
        let client = CocoaMQTT(clientID: deviceID, host: "89.167.29.191", port: 1883)
        client.keepAlive = 60
        client.autoReconnect = true
        client.willMessage = CocoaMQTTMessage(topic: "esp32-udemy/telemovel/estado",
                                              string: "offline", qos: .qos1, retained: true)
        client.didConnectAck = { [weak self] client, ack in
            DispatchQueue.main.async {
                guard let self = self, self.active else { return }
                self.connected = ack == .accept
                self.mqttStatus = self.connected ? "MQTT ligado" : "MQTT recusado: \(ack)"
                if self.connected {
                    client.subscribe("esp32/cmd", qos: .qos1)
                    client.subscribe("esp32/acelerometro/config/set", qos: .qos1)
                    self.publish("esp32-udemy/telemovel/estado", text: "online", qos: .qos1, retained: true)
                    self.publishConfig()
                    if let position = self.lastLocation { self.sendLocation(position) }
                }
            }
        }
        client.didReceiveMessage = { [weak self] _, message, _ in
            let text = (message.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let topic = message.topic
            DispatchQueue.main.async {
                guard let self = self, self.active else { return }
                if topic == "esp32/cmd", text.uppercased() == "ZERO" {
                    self.setReference()
                } else if topic == "esp32/acelerometro/config/set", let ms = Int(text) {
                    self.applyInterval(ms)
                }
            }
        }
        client.didDisconnect = { [weak self] _, _ in
            DispatchQueue.main.async {
                guard let self = self, self.active, self.mqttOn else { return }
                self.connected = false
                self.mqttStatus = "MQTT desligado; a tentar novamente"
            }
        }
        mqtt = client
        mqttOn = true
        mqttStatus = "MQTT a ligar"
        _ = client.connect()
    }

    private func disconnectMQTT() {
        publish("esp32-udemy/telemovel/estado", text: "offline", qos: .qos1, retained: true)
        mqttOn = false
        mqtt?.disconnect()
        mqtt = nil
        connected = false
        mqttStatus = "MQTT desligado"
    }

    private func publish(_ topic: String, text: String, qos: CocoaMQTTQoS = .qos0, retained: Bool = false) {
        guard connected else { return }
        mqtt?.publish(topic, withString: text, qos: qos, retained: retained)
    }

    private func publishJSON(_ topic: String, _ value: [String: Any], qos: CocoaMQTTQoS = .qos0) {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value),
              let text = String(data: data, encoding: .utf8) else { return }
        publish(topic, text: text, qos: qos)
    }

    private func publishConfig() {
        publish("esp32/acelerometro/config", text: "{\"period_ms\":\(intervalMs),\"sensor_on\":true}", qos: .qos1)
    }

    private var timestamp: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: - GPS e morada

    private func startLocationIfAllowed() {
        guard active else { return }
        if location.authorizationStatus == .authorizedAlways || location.authorizationStatus == .authorizedWhenInUse {
            location.startUpdatingLocation()
            // Se o iOS fechar a app por falta de memória, uma mudança significativa de
            // posição volta a abri-la em segundo plano (exige "Sempre" na localização).
            if location.authorizationStatus == .authorizedAlways {
                location.startMonitoringSignificantLocationChanges()
            }
            gpsStatus = "GPS à espera de posição"
        } else {
            gpsStatus = "Autorize a localização"
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { startLocationIfAllowed() }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard active, let position = locations.last else { return }
        lastLocation = position
        gpsStatus = String(format: "GPS: %.6f, %.6f", position.coordinate.latitude, position.coordinate.longitude)
        gpsDetail = String(format: "±%.0f m (GPS) às %@", position.horizontalAccuracy,
                           clock.string(from: position.timestamp))
        if Date().timeIntervalSince(lastGpsSent) >= 2 { sendLocation(position) }
        lookUpAddress(position)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        gpsStatus = "GPS: \(error.localizedDescription)"
    }

    private func lookUpAddress(_ position: CLLocation) {
        if let previous = lastGeocoded, position.distance(from: previous) < 20 { return }
        guard !geocoder.isGeocoding, Date().timeIntervalSince(lastGeocodeTime) > 10 else { return }
        lastGeocodeTime = Date()
        geocoder.reverseGeocodeLocation(position, preferredLocale: Locale(identifier: "pt_PT")) { [weak self] places, _ in
            guard let self = self, let p = places?.first else { return }
            var street = [p.thoroughfare, p.subThoroughfare].compactMap { $0 }.joined(separator: " ")
            let town = [p.postalCode, p.locality].compactMap { $0 }.joined(separator: " ")
            for part in [town, p.country ?? ""] where !part.isEmpty {
                street += street.isEmpty ? part : ", " + part
            }
            DispatchQueue.main.async {
                self.lastGeocoded = position
                self.address = street
            }
        }
    }

    private func sendLocation(_ position: CLLocation) {
        guard connected else { return }
        lastGpsSent = Date()
        secondsSinceGps = 0
        let battery = UIDevice.current.batteryLevel
        publishJSON("fastapi/map/positions", [
            "device_id": deviceID, "label": name,
            "lat": position.coordinate.latitude, "lon": position.coordinate.longitude,
            "precisao": position.horizontalAccuracy.rounded(), "fonte": "GPS",
            "bateria": battery < 0 ? (NSNull() as Any) : (Int(battery * 100) as Any),
            "a_carregar": UIDevice.current.batteryState == .charging,
            "ts": timestamp
        ])
    }

    // MARK: - Alertas

    /// Mensagem para o chat de alertas da página web (tópico que o servidor FastAPI escuta).
    private func sendAlert(_ text: String) {
        var content = text
        if let c = lastLocation?.coordinate {
            content += String(format: " https://maps.google.com/?q=%.6f,%.6f", c.latitude, c.longitude)
        }
        lastEvent = text
        publishJSON("fastapi/chat/alerts", [
            "room_id": "geral", "content": content, "sender": name, "device_id": deviceID
        ], qos: .qos1)
    }

    /// Regra da versão Android: > 40° desde a referência durante 1 s -> ALERTA;
    /// < 40° durante 1 s -> "Está tudo bem!".
    private func checkInclination(_ inclination: Double) {
        let now = Date()
        if inclination > inclinationAlert {
            belowSince = nil
            if aboveSince == nil { aboveSince = now }
            if !inAlert, let since = aboveSince, now.timeIntervalSince(since) >= 1 {
                inAlert = true
                let fell = lastFall.map { now.timeIntervalSince($0) <= 10 } ?? false
                sendAlert(String(format: "ALERTA: %@ %@(inclinação %.0f°)",
                                 name, fell ? "QUEDA DETETADA " : "", inclination))
            }
        } else {
            aboveSince = nil
            if belowSince == nil { belowSince = now }
            if inAlert, let since = belowSince, now.timeIntervalSince(since) >= 1 {
                inAlert = false
                sendAlert(String(format: "ALERTA: %@ Está tudo bem! (inclinação %.0f°)", name, inclination))
            }
        }
    }

    // MARK: - Acelerómetro

    private func absoluteRollPitch() -> (Double, Double) {
        let gx = gravity[0], gy = gravity[1], gz = gravity[2]
        return (atan2(gy, gz) * 180 / .pi, atan2(-gx, sqrt(gy * gy + gz * gz)) * 180 / .pi)
    }

    private func between180(_ v: Double) -> Double {
        var a = v.truncatingRemainder(dividingBy: 360)
        if a > 180 { a -= 360 }
        if a < -180 { a += 360 }
        return a
    }

    private var yawValue: Double? {
        guard let rawYaw = rawYaw else { return nil }
        return referenceYaw.map { between180(rawYaw - $0) } ?? rawYaw
    }

    /// Ângulo entre a gravidade agora e a gravidade na referência (nil sem referência).
    private func angleFromReference() -> Double? {
        guard let r = refGravity, !firstSample else { return nil }
        let g = gravity
        let ng = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        let nr = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
        guard ng > 0.1, nr > 0.1 else { return nil }
        let c = max(-1, min(1, (g[0] * r[0] + g[1] * r[1] + g[2] * r[2]) / (ng * nr)))
        return acos(c) * 180 / .pi
    }

    private func startMotion() {
        guard motion.isAccelerometerAvailable else {
            motionStatus = "Sem acelerómetro"
            return
        }
        firstSample = true
        rawYaw = nil; referenceYaw = nil; rotationZ = nil; rotationSpeed = 0
        fall = FallRule()
        aboveSince = nil; belowSince = nil; inAlert = false; lastFall = nil
        if motion.isDeviceMotionAvailable {
            let available = CMMotionManager.availableAttitudeReferenceFrames()
            let frame: CMAttitudeReferenceFrame = available.contains(.xMagneticNorthZVertical)
                ? .xMagneticNorthZVertical : .xArbitraryZVertical
            motion.deviceMotionUpdateInterval = 0.02
            motion.startDeviceMotionUpdates(using: frame)
        }
        motion.accelerometerUpdateInterval = 0.02
        motion.startAccelerometerUpdates(to: .main) { [weak self] sample, _ in
            guard let self = self, self.active, let sample = sample else { return }
            if let orientation = self.motion.deviceMotion {
                let heading = orientation.heading
                let yaw = heading >= 0 ? heading :
                    (orientation.attitude.yaw * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
                self.rawYaw = yaw
                if self.refGravity != nil && self.referenceYaw == nil { self.referenceYaw = yaw }
                let rate = orientation.rotationRate
                self.rotationSpeed = sqrt(rate.x * rate.x + rate.y * rate.y + rate.z * rate.z) * 180 / .pi
            }
            // O iOS dá a gravidade com o sinal contrário do Android: inverte-se para
            // que, deitado de costas, z = +9,8 e a inclinação seja 0° (como no Android).
            let a = [-sample.acceleration.x * 9.80665,
                     -sample.acceleration.y * 9.80665,
                     -sample.acceleration.z * 9.80665]
            if self.firstSample { self.gravity = a; self.firstSample = false }
            else { for i in 0..<3 { self.gravity[i] = 0.8 * self.gravity[i] + 0.2 * a[i] } }
            let x = a[0] - self.gravity[0], y = a[1] - self.gravity[1], z = a[2] - self.gravity[2]
            let g = sqrt(self.gravity.reduce(0) { $0 + $1 * $1 })
            let inclination = g > 0.1 ? acos(max(-1, min(1, self.gravity[2] / g))) * 180 / .pi : 0
            self.lastMotion = (x, y, z, inclination)
            // Sem referência: define-a sozinha ao fim de 3 s (como no Android)
            if self.refGravity == nil, Date().timeIntervalSince(self.startedAt) >= 3 { self.setReference() }
            if let fromRef = self.angleFromReference() { self.checkInclination(fromRef) }
            let total = sqrt(a.reduce(0) { $0 + $1 * $1 })
            if self.fall.update(total: total, at: Int64(sample.timestamp * 1000)) {
                self.lastFall = Date()
                self.sendAlert("ALERTA: \(self.name) QUEDA DETETADA")
                self.publishJSON("esp32-udemy/telemovel/queda", [
                    "nome": self.name, "device_id": self.deviceID,
                    "queda": true, "ts": self.timestamp
                ], qos: .qos1)
            }
        }
    }

    /// Atualiza os números do ecrã 4 vezes por segundo (não a cada leitura, para poupar bateria).
    private func refreshScreen() {
        if let values = lastMotion {
            axisX = values.0; axisY = values.1; axisZ = values.2
            inclinationNow = values.3
            rotationZ = yawValue
            motionStatus = String(format: "Inclinação %.0f°", values.3)
        }
        inclinationFromRef = angleFromReference()
        let battery = UIDevice.current.batteryLevel
        batteryText = battery < 0 ? "" : "Bateria: \(Int(battery * 100))%" +
            (UIDevice.current.batteryState == .charging ? " (a carregar)" : "")
    }

    private func restartGraphTimer() {
        graphTimer?.invalidate()
        guard active else { return }
        graphTimer = Timer.scheduledTimer(withTimeInterval: Double(intervalMs) / 1000, repeats: true) { [weak self] _ in
            self?.sendGraph()
        }
    }

    /// Mesmo tópico e formato que o Android usa para a inclinação no mapa e a página "Gráficos".
    private func sendGraph() {
        guard active, let values = lastMotion else { return }
        var (roll, pitch) = absoluteRollPitch()
        if refGravity != nil { roll = between180(roll - refRoll); pitch -= refPitch }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let yaw = yawValue
        publishJSON("esp32/acelerometro", [
            "lx": values.0, "ly": values.1, "lz": values.2,
            "roll": roll, "pitch": pitch,
            "yaw": yaw ?? 0, "yaw_ok": yaw == nil ? 0 : 1, "sample_utc": iso.string(from: Date())
        ])
    }

    private func everySecond() {
        guard active else { return }
        if let values = lastMotion {
            let battery = UIDevice.current.batteryLevel
            publishJSON("esp32-udemy/telemovel/dados", [
                "nome": name, "device_id": deviceID,
                "x": values.0, "y": values.1, "z": values.2,
                "inclinacao": values.3, "rotacao": rotationSpeed,
                "horizontal": values.3 < 15 || values.3 > 165,
                "bateria": battery < 0 ? -1 : Int(battery * 100),
                "a_carregar": UIDevice.current.batteryState == .charging,
                "ts": timestamp
            ])
        }
        secondsSinceGps += 1
        if secondsSinceGps >= resendGpsEvery, let position = lastLocation { sendLocation(position) }
    }

    // MARK: - Botão Shelly (Bluetooth)

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard active else { return }
        if central.state == .poweredOn {
            bluetoothStatus = "À escuta do botão Shelly"
            central.scanForPeripherals(withServices: [bthome],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        } else {
            bluetoothStatus = "Ligue o Bluetooth"
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard active,
              let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let data = serviceData[bthome],
              let reading = BTHomeReading.parse(data), !reading.encrypted, reading.button > 0 else { return }
        let id = peripheral.identifier.uuidString
        let now = Date()
        if let previous = lastPackets[id], previous.0 == reading.packet,
           now.timeIntervalSince(previous.1) < 10 { return }
        lastPackets[id] = (reading.packet, now)
        var paired = Set(UserDefaults.standard.stringArray(forKey: "shellyIDs") ?? [])
        if let until = associateUntil, now <= until {
            paired.insert(id)
            UserDefaults.standard.set(Array(paired), forKey: "shellyIDs")
            associateUntil = nil
            bluetoothStatus = "Botão associado"
            return
        }
        guard paired.contains(id) else { return }
        let event = BTHomeReading.description(reading.button)
        lastEvent = "Shelly: \(event)"
        shellyText = "Botão Shelly: \"\(event)\" às \(clock.string(from: now))" +
            (reading.battery >= 0 ? " · 🔋 \(reading.battery)%" : "") + " · \(RSSI.intValue) dBm"
        publishJSON("esp32-udemy/telemovel/botao", [
            "nome": name, "device_id": deviceID, "botao": id,
            "evento": event, "bateria_botao": reading.battery,
            "rssi": RSSI.intValue, "ts": timestamp
        ], qos: .qos1)
    }
}

private struct BTHomeReading {
    let encrypted: Bool
    let packet: Int
    let battery: Int
    let button: Int

    static func description(_ button: Int) -> String {
        switch button {
        case 1: return "carregar"
        case 2: return "duplo"
        case 3: return "triplo"
        case 4: return "longo"
        case 0x80, 0xFE: return "manter"
        default: return "evento \(button)"
        }
    }

    static func parse(_ data: Data) -> BTHomeReading? {
        let bytes = Array(data)
        guard let info = bytes.first, (info >> 5) == 2 else { return nil }
        if info & 1 != 0 { return BTHomeReading(encrypted: true, packet: -1, battery: -1, button: -1) }
        var packet = -1, battery = -1, button = -1, index = 1
        while index < bytes.count {
            let id = Int(bytes[index]); index += 1
            let size: Int
            switch id {
            case 0x00, 0x01, 0x09, 0x2E, 0x2F, 0x3A, 0x46, 0x0F...0x11, 0x15...0x2D: size = 1
            case 0x02, 0x03, 0x06, 0x07, 0x08, 0x0C, 0x0D, 0x0E,
                 0x12, 0x13, 0x14, 0x3C, 0x3D, 0x3F, 0x40, 0x41,
                 0x43, 0x44, 0x45, 0x47, 0xF0: size = 2
            case 0x04, 0x05, 0x0A, 0x0B, 0x42, 0x4A, 0xF2: size = 3
            case 0x3E, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0xF1: size = 4
            default: return BTHomeReading(encrypted: false, packet: packet, battery: battery, button: button)
            }
            guard index + size <= bytes.count else { break }
            let value = Int(bytes[index])
            if id == 0x00 { packet = value }
            if id == 0x01 { battery = value }
            if id == 0x3A && button <= 0 { button = value }
            index += size
        }
        return BTHomeReading(encrypted: false, packet: packet, battery: battery, button: button)
    }
}

private struct FallRule {
    private var start = Int64(0)
    private var end = Int64(0)
    private var hadFreeFall = false
    private var lastAlert = Int64(0)

    mutating func update(total: Double, at time: Int64) -> Bool {
        if total < 3 {
            if start == 0 { start = time }
            if time - start >= 80 { hadFreeFall = true; end = time }
        } else { start = 0 }
        guard hadFreeFall else { return false }
        if time - end > 1000 { hadFreeFall = false; return false }
        if total > 20 {
            hadFreeFall = false
            if time - lastAlert > 2000 { lastAlert = time; return true }
        }
        return false
    }
}
