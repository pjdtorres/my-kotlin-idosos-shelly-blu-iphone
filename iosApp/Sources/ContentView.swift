import SwiftUI
import MessageUI

/// Ecrã compacto, com a mesma ordem da versão Android, para caber num iPhone.
struct ContentView: View {
    @ObservedObject private var monitor = MonitorController.shared
    @AppStorage("nome") private var nomeGuardado = "Telemóvel"
    @AppStorage("contacto") private var contactoGuardado = ""
    @State private var nome = ""
    @State private var contacto = ""
    @State private var intervalo = ""
    @State private var aviso = ""
    @State private var mostrarSMS = false
    @FocusState private var foco: Bool

    private let roxo = Color(red: 0.75, green: 0.55, blue: 0.98)

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                // Título + estado do MQTT
                HStack(spacing: 6) {
                    Text("Monitorização").font(.headline)
                    Text("· \(monitor.mqttStatus)").font(.subheadline).lineLimit(1)
                    Spacer(minLength: 0)
                }

                Button(monitor.active ? "Parar monitorização" : "Iniciar monitorização") {
                    foco = false
                    if monitor.active { monitor.stop() } else { monitor.start(name: nomeGuardado) }
                }
                .buttonStyle(.borderedProminent)
                .tint(monitor.active ? .gray : .green)
                .controlSize(.small)
                .frame(maxWidth: .infinity, alignment: .leading)

                // Nome, intervalo e número (cada um com o seu botão, como no Android)
                linha("Guardar", acao: {
                    nomeGuardado = nome.isEmpty ? "Telemóvel" : nome
                    monitor.setName(nomeGuardado)
                    aviso = "Nome guardado"
                }) {
                    TextField("Nome", text: $nome)
                }

                linha("Aplicar", acao: {
                    if let ms = Int(intervalo), monitor.applyInterval(ms) {
                        aviso = String(format: "Intervalo de leitura: %d ms (%.2f Hz)", ms, 1000.0 / Double(ms))
                    } else {
                        aviso = "Intervalo entre 10 e 1000 ms"
                        intervalo = "\(monitor.intervalMs)"
                    }
                }) {
                    HStack(spacing: 4) {
                        Text("Intervalo (ms):").font(.footnote).foregroundStyle(.secondary)
                        TextField("100", text: $intervalo).keyboardType(.numberPad)
                    }
                }

                linha("Guardar", acao: {
                    contactoGuardado = contacto
                    aviso = "Número guardado"
                }) {
                    TextField("Número para SMS", text: $contacto).keyboardType(.phonePad)
                }

                if !aviso.isEmpty {
                    Text(aviso).font(.caption).foregroundStyle(.secondary)
                }

                // Referência
                Text("Pouse o telemóvel na posição de repouso e carregue no botão")
                    .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button("🎯 Definir referência") { monitor.setReference() }
                    .buttonStyle(.bordered).tint(.green).controlSize(.small)
                    .disabled(!monitor.active)

                // GPS e morada
                VStack(spacing: 2) {
                    Text(monitor.gpsStatus).font(.subheadline.bold()).foregroundStyle(.blue)
                    if !monitor.gpsDetail.isEmpty {
                        Text(monitor.gpsDetail).font(.footnote).foregroundStyle(.blue)
                    }
                    if !monitor.address.isEmpty {
                        Text(monitor.address).font(.footnote.bold()).foregroundStyle(.green)
                    }
                }
                .multilineTextAlignment(.center)

                // MQTT e SMS
                HStack(spacing: 8) {
                    Button(monitor.mqttOn ? "Desligar MQTT" : "Ligar MQTT") { monitor.toggleMQTT() }
                        .buttonStyle(.borderedProminent)
                        .tint(monitor.mqttOn ? .red : .green)
                        .disabled(!monitor.active)
                    Button("📱 Ligar telemóvel") { mostrarSMS = true }
                        .buttonStyle(.borderedProminent)
                        .tint(.orange)
                        .disabled(contactoGuardado.isEmpty || !MFMessageComposeViewController.canSendText())
                }
                .controlSize(.small)

                if !monitor.batteryText.isEmpty {
                    Text("🔋 \(monitor.batteryText)").font(.footnote).foregroundStyle(.green)
                }

                // Botão Shelly
                Text(monitor.shellyText).font(.footnote).foregroundStyle(.blue)
                    .multilineTextAlignment(.center)
                HStack(spacing: 12) {
                    Button("Associar botão") { monitor.associateButton() }
                        .buttonStyle(.borderedProminent).tint(roxo)
                        .disabled(!monitor.active)
                    Button("Esquecer") { monitor.forgetButtons() }
                        .foregroundStyle(roxo)
                }
                .controlSize(.small)
                Text(monitor.bluetoothStatus).font(.caption).foregroundStyle(.secondary)

                // Acelerómetro
                Text(String(format: "X: %.1f   Y: %.1f   Z: %.1f", monitor.axisX, monitor.axisY, monitor.axisZ))
                    .font(.title3.monospacedDigit())
                Text(String(format: "Inclinação: %.0f°", monitor.inclinationNow))
                    .font(.title3)
                ProgressView(value: min(max(monitor.inclinationNow, 0), 180), total: 180)
                    .tint(.teal)
                if let ref = monitor.inclinationFromRef {
                    Text(String(format: ref > 40 ? "Desde a referência: %.0f° (ALERTA)" : "Desde a referência: %.0f°", ref))
                        .font(.subheadline.bold())
                        .foregroundStyle(ref > 40 ? .red : .green)
                }

                Text(monitor.lastEvent).font(.footnote)
                Text("O SMS do botão laranja tem de ser confirmado no ecrã de Mensagens. Os alertas automáticos (inclinação, queda) vão para o chat da página web.")
                    .font(.caption2).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear {
            nome = nomeGuardado == "Telemóvel" ? "" : nomeGuardado
            contacto = contactoGuardado
            intervalo = "\(monitor.intervalMs)"
        }
        .sheet(isPresented: $mostrarSMS) {
            MessageComposer(recipient: contactoGuardado, body: monitor.helpMessage(name: nomeGuardado))
        }
    }

    /// Campo de texto + botão roxo à direita (como as linhas do Android).
    private func linha<Campo: View>(_ titulo: String, acao: @escaping () -> Void,
                                    @ViewBuilder campo: () -> Campo) -> some View {
        HStack(spacing: 8) {
            campo()
                .textFieldStyle(.roundedBorder)
                .focused($foco)
            Button(titulo) { foco = false; acao() }
                .buttonStyle(.borderedProminent)
                .tint(roxo)
                .foregroundStyle(.black)
                .controlSize(.small)
                .frame(width: 90)
        }
    }
}

private struct MessageComposer: UIViewControllerRepresentable {
    let recipient: String
    let body: String
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let controller = MFMessageComposeViewController()
        controller.recipients = [recipient]
        controller.body = body
        controller.messageComposeDelegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: MFMessageComposeViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator { dismiss() } }

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let done: () -> Void
        init(done: @escaping () -> Void) { self.done = done }
        func messageComposeViewController(_ controller: MFMessageComposeViewController,
                                          didFinishWith result: MessageComposeResult) { done() }
    }
}
