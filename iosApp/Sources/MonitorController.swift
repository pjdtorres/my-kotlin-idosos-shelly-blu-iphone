import SwiftUI
import CoreLocation
import CoreBluetooth
import CoreMotion
import CocoaMQTT

final class MonitorController: NSObject, ObservableObject, CLLocationManagerDelegate, CBCentralManagerDelegate {
    @Published private(set) var active = false
    @Published private(set) var mqttStatus = "MQTT desligado"
    @Published private(set) var gpsStatus = "GPS desligado"
    @Published private(set) var motionStatus = "Movimento desligado"
    @Published private(set) var bluetoothStatus = "Bluetooth desligado"
    @Published private(set) var lastEvent = "Sem eventos"

    private let location = CLLocationManager()
    private let motion = CMMotionManager()
    private var bluetooth: CBCentralManager?
    private var mqtt: CocoaMQTT?
    private var timer: Timer?
    private var connected = false
    private var name = "Telemóvel"
    private var lastLocation: CLLocation?
    private var lastMotion: (Double, Double, Double, Double)?
    private var gravity = [Double](repeating: 0, count: 3)
    private var firstSample = true
    private var fall = FallRule()
    private var associateUntil: Date?
    private var lastPackets: [String: (Int, Date)] = [:]
    private let bthome = CBUUID(string: "FCD2")

    private var deviceID: String {
        let key = "iphoneDeviceID"
        if let saved = UserDefaults.standard.string(forKey: key) { return saved }
        let value = "telemovel-iphone-" + UUID().uuidString.prefix(8).lowercased()
        UserDefaults.standard.set(value, forKey: key)
        return value
    }

    func start(name: String) {
        guard !active else { return }
        self.name = name.isEmpty ? "Telemóvel" : name
        active = true
        UIDevice.current.isBatteryMonitoringEnabled = true
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBest
        location.requestWhenInUseAuthorization()
        startLocationIfAllowed()
        bluetooth = CBCentralManager(delegate: self, queue: nil)
        startMotion()
        connectMQTT()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.sendMotion()
        }
    }

    func stop() {
        guard active else { return }
        active = false
        timer?.invalidate()
        timer = nil
        location.stopUpdatingLocation()
        motion.stopAccelerometerUpdates()
        bluetooth?.stopScan()
        publish("esp32-udemy/telemovel/estado", text: "offline", qos: .qos1, retained: true)
        mqtt?.disconnect()
        mqtt = nil
        connected = false
        mqttStatus = "MQTT desligado"
        gpsStatus = "GPS desligado"
        motionStatus = "Movimento desligado"
        bluetoothStatus = "Bluetooth desligado"
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
        if let coordinate = lastLocation?.coordinate {
            message += "\nhttps://maps.google.com/?q=\(coordinate.latitude),\(coordinate.longitude)"
        }
        return message
    }

    private func connectMQTT() {
        let client = CocoaMQTT(clientID: deviceID, host: "89.167.29.191", port: 1883)
        client.keepAlive = 60
        client.autoReconnect = true
        client.didConnectAck = { [weak self] _, ack in
            DispatchQueue.main.async {
                guard let self = self, self.active else { return }
                self.connected = ack == .accept
                self.mqttStatus = self.connected ? "MQTT ligado" : "MQTT recusado: \(ack)"
                if self.connected {
                    self.publish("esp32-udemy/telemovel/estado", text: "online", qos: .qos1, retained: true)
                    if let position = self.lastLocation { self.sendLocation(position) }
                }
            }
        }
        client.didDisconnect = { [weak self] _, _ in
            DispatchQueue.main.async {
                guard let self = self, self.active else { return }
                self.connected = false
                self.mqttStatus = "MQTT desligado; a tentar novamente"
            }
        }
        mqtt = client
        mqttStatus = "MQTT a ligar"
        _ = client.connect()
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

    private var timestamp: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func startLocationIfAllowed() {
        guard active else { return }
        if location.authorizationStatus == .authorizedAlways || location.authorizationStatus == .authorizedWhenInUse {
            location.startUpdatingLocation()
            gpsStatus = "GPS à espera de posição"
        } else {
            gpsStatus = "Autorize a localização"
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { startLocationIfAllowed() }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard active, let position = locations.last else { return }
        lastLocation = position
        gpsStatus = String(format: "GPS %.5f, %.5f", position.coordinate.latitude, position.coordinate.longitude)
        sendLocation(position)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        gpsStatus = "GPS: \(error.localizedDescription)"
    }

    private func sendLocation(_ position: CLLocation) {
        publishJSON("fastapi/map/positions", [
            "device_id": deviceID, "label": name,
            "lat": position.coordinate.latitude, "lon": position.coordinate.longitude,
            "precisao": position.horizontalAccuracy, "fonte": "GPS", "ts": timestamp
        ])
    }

    private func startMotion() {
        guard motion.isAccelerometerAvailable else {
            motionStatus = "Sem acelerómetro"
            return
        }
        firstSample = true
        fall = FallRule()
        motion.accelerometerUpdateInterval = 0.02
        motion.startAccelerometerUpdates(to: .main) { [weak self] sample, _ in
            guard let self = self, self.active, let sample = sample else { return }
            let a = [sample.acceleration.x * 9.80665,
                     sample.acceleration.y * 9.80665,
                     sample.acceleration.z * 9.80665]
            if self.firstSample { self.gravity = a; self.firstSample = false }
            else { for i in 0..<3 { self.gravity[i] = 0.8 * self.gravity[i] + 0.2 * a[i] } }
            let x = a[0] - self.gravity[0], y = a[1] - self.gravity[1], z = a[2] - self.gravity[2]
            let g = sqrt(self.gravity.reduce(0) { $0 + $1 * $1 })
            let inclination = g > 0.1 ? acos(max(-1, min(1, self.gravity[2] / g))) * 180 / .pi : 0
            self.lastMotion = (x, y, z, inclination)
            self.motionStatus = String(format: "Inclinação %.0f°", inclination)
            let total = sqrt(a.reduce(0) { $0 + $1 * $1 })
            if self.fall.update(total: total, at: Int64(sample.timestamp * 1000)) {
                self.lastEvent = "Possível queda detetada"
                self.publishJSON("esp32-udemy/telemovel/queda", [
                    "nome": self.name, "device_id": self.deviceID,
                    "queda": true, "ts": self.timestamp
                ], qos: .qos1)
            }
        }
    }

    private func sendMotion() {
        guard active, let values = lastMotion else { return }
        let battery = UIDevice.current.batteryLevel
        publishJSON("esp32-udemy/telemovel/dados", [
            "nome": name, "device_id": deviceID,
            "x": values.0, "y": values.1, "z": values.2,
            "inclinacao": values.3, "rotacao": 0,
            "horizontal": values.3 < 15 || values.3 > 165,
            "bateria": battery < 0 ? -1 : Int(battery * 100),
            "a_carregar": UIDevice.current.batteryState == .charging,
            "ts": timestamp
        ])
    }

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
