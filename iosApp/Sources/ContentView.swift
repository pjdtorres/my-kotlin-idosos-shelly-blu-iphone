import SwiftUI
import MessageUI

struct ContentView: View {
    @StateObject private var monitor = MonitorController()
    @AppStorage("nome") private var nome = "Telemóvel"
    @AppStorage("contacto") private var contacto = ""
    @State private var mostrarSMS = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Pessoa") {
                    TextField("Nome", text: $nome)
                    TextField("Número para SMS", text: $contacto)
                        .keyboardType(.phonePad)
                }
                Section("Monitorização") {
                    Button(monitor.active ? "Parar" : "Iniciar") {
                        if monitor.active { monitor.stop() }
                        else { monitor.start(name: nome) }
                    }
                    Text(monitor.mqttStatus)
                    Text(monitor.gpsStatus)
                    Text(monitor.motionStatus)
                    Text(monitor.bluetoothStatus)
                    Text(monitor.lastEvent)
                    Text("A deteção de quedas usa o acelerómetro enquanto a aplicação está ativa. Teste-a no iPhone antes de depender dos alertas.")
                        .font(.footnote)
                }
                Section("Botão Shelly") {
                    Button("Associar botão durante 30 s") { monitor.associateButton() }
                        .disabled(!monitor.active)
                    Button("Esquecer botões") { monitor.forgetButtons() }
                }
                Section("Ajuda") {
                    Button("ALERTA! Preparar SMS") { mostrarSMS = true }
                        .disabled(contacto.isEmpty || !MFMessageComposeViewController.canSendText())
                    Text("No iPhone, confirme o envio do SMS no ecrã de Mensagens. Os avisos automáticos são publicados por MQTT.")
                        .font(.footnote)
                }
            }
            .navigationTitle("Idosos Avalon")
            .sheet(isPresented: $mostrarSMS) {
                MessageComposer(recipient: contacto, body: monitor.helpMessage(name: nome))
            }
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
