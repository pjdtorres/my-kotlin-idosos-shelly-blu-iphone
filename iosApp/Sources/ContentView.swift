import SwiftUI
import MessageUI

/// Ecrã iPhone com a mesma ordem e identidade visual da monitorização Android.
struct ContentView: View {
    @ObservedObject private var monitor = MonitorController.shared

    @AppStorage("nome") private var nomeGuardado = "Telemóvel"
    @AppStorage("contacto") private var contactoGuardado = ""

    @State private var nome = ""
    @State private var contacto = ""
    @State private var intervalo = ""
    @State private var aviso = ""
    @State private var mostrarSMS = false
    @State private var confirmarEsquecer = false

    private enum Campo: Hashable { case nome, intervalo, contacto }
    @FocusState private var campoAtivo: Campo?

    private let fundo = Color(red: 0.055, green: 0.055, blue: 0.055)
    private let roxo = Color(red: 0.72, green: 0.51, blue: 0.96)
    private let azul = Color(red: 0.16, green: 0.58, blue: 0.94)
    private let verde = Color(red: 0.03, green: 0.46, blue: 0.12)
    private let turquesa = Color(red: 0.00, green: 0.78, blue: 0.72)

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Text("Monitorização  ·  \(monitor.mqttStatus)")
                    .font(.system(size: 21, weight: .medium))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 22)
                    .padding(.bottom, 18)

                if !monitor.active {
                    Button {
                        campoAtivo = nil
                        monitor.start(name: nomeGuardado)
                    } label: {
                        Text("INICIAR MONITORIZAÇÃO")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.black)
                            .frame(maxWidth: .infinity)
                            .frame(height: 42)
                            .background(Color.green, in: RoundedRectangle(cornerRadius: 6))
                    }
                    .buttonStyle(.plain)
                    .padding(.bottom, 18)
                }

                VStack(spacing: 12) {
                    linha("GUARDAR") {
                        nomeGuardado = nome.isEmpty ? "Telemóvel" : nome
                        monitor.setName(nomeGuardado)
                        aviso = "Nome guardado"
                    } campo: {
                        TextField("Nome da pessoa", text: $nome)
                            .textContentType(.name)
                            .textInputAutocapitalization(.words)
                            .focused($campoAtivo, equals: .nome)
                            .modifier(CampoSublinhado())
                    }

                    linha("APLICAR") {
                        if let ms = Int(intervalo), monitor.applyInterval(ms) {
                            aviso = "Intervalo de leitura: \(ms) ms"
                        } else {
                            aviso = "Intervalo entre 10 e 1000 ms"
                            intervalo = "\(monitor.intervalMs)"
                        }
                    } campo: {
                        HStack(spacing: 4) {
                            ViewThatFits(in: .horizontal) {
                                Text("Intervalo de leitura (ms):")
                                Text("Intervalo (ms):")
                            }
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.7))
                            TextField("100", text: $intervalo)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                                .focused($campoAtivo, equals: .intervalo)
                                .frame(width: 55)
                        }
                        .modifier(CampoSublinhado())
                    }

                    linha("GUARDAR") {
                        contactoGuardado = contacto
                        aviso = "Número guardado"
                    } campo: {
                        TextField("Telemóvel para SMS", text: $contacto)
                            .keyboardType(.phonePad)
                            .textContentType(.telephoneNumber)
                            .focused($campoAtivo, equals: .contacto)
                            .modifier(CampoSublinhado())
                    }
                }

                if !aviso.isEmpty {
                    Text(aviso)
                        .font(.caption)
                        .foregroundStyle(.yellow)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 7)
                }

                Text("Pouse o telemóvel na posição de repouso e carregue no botão")
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.65))
                    .multilineTextAlignment(.center)
                    .padding(.top, 24)

                Button {
                    monitor.setReference()
                } label: {
                    Text("🎯  Definir referência")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Color(red: 0.17, green: 0.62, blue: 0.35))
                        .padding(.horizontal, 20)
                        .frame(height: 40)
                        .overlay {
                            Capsule().stroke(Color(red: 0.17, green: 0.62, blue: 0.35), lineWidth: 1)
                        }
                        .contentShape(Capsule())   // todo o contorno reage ao toque
                }
                .buttonStyle(.plain)
                .disabled(!monitor.active)
                .opacity(monitor.active ? 1 : 0.5)
                .padding(.top, 10)

                if !monitor.referenceText.isEmpty {
                    Text(monitor.referenceText)
                        .font(.system(size: 13))
                        .foregroundStyle(Color(red: 0.17, green: 0.62, blue: 0.35))
                        .padding(.top, 4)
                }

                VStack(spacing: 1) {
                    Text(monitor.gpsStatus)
                        .font(.system(size: 19, weight: .bold))
                        .foregroundStyle(azul)
                    if !monitor.gpsDetail.isEmpty {
                        Text(monitor.gpsDetail)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(azul)
                    }
                    if !monitor.address.isEmpty {
                        Text(monitor.address)
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(verde)
                    }
                }
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 27)

                // MQTT e ALERTA lado a lado
                HStack(spacing: 10) {
                    Button {
                        monitor.toggleMQTT()
                    } label: {
                        Text(monitor.mqttOn ? "DESLIGAR MQTT" : "LIGAR MQTT")
                            .font(.system(size: 16, weight: .semibold))
                            .tracking(1)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity)
                            .frame(height: 42)
                            .background(monitor.mqttOn ? Color(red: 0.84, green: 0.18, blue: 0.18) :
                                        Color(red: 0.13, green: 0.55, blue: 0.22),
                                        in: RoundedRectangle(cornerRadius: 5))
                    }
                    .buttonStyle(.plain)
                    .disabled(!monitor.active)

                    Button {
                        mostrarSMS = true
                    } label: {
                        Text("ALERTA")
                            .font(.system(size: 16, weight: .semibold))
                            .tracking(1)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity)
                            .frame(height: 42)
                            .background(Color(red: 0.94, green: 0.42, blue: 0.00),
                                        in: RoundedRectangle(cornerRadius: 5))
                    }
                    .buttonStyle(.plain)
                    .disabled(contactoGuardado.isEmpty || !MFMessageComposeViewController.canSendText())
                }
                .opacity(monitor.active ? 1 : 0.65)
                .padding(.top, 27)

                if !monitor.batteryText.isEmpty {
                    Text("🔋 \(monitor.batteryText)")
                        .font(.system(size: 18))
                        .foregroundStyle(verde)
                        .padding(.top, 17)
                }

                Text("🔘 \(monitor.shellyText)")
                    .font(.system(size: 15))
                    .foregroundStyle(azul)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 17)

                HStack(spacing: 18) {
                    Button {
                        monitor.associateButton()
                    } label: {
                        Text("Associar botão")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 18)
                            .frame(height: 43)
                            .background(roxo, in: RoundedRectangle(cornerRadius: 5))
                    }
                    .buttonStyle(.plain)
                    .disabled(!monitor.active)

                    Button("Esquecer") {
                        confirmarEsquecer = true
                    }
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(roxo)
                }
                .padding(.top, 9)

                Text(String(format: "X: %.1f   Y: %.1f   Z: %.1f",
                            monitor.axisX, monitor.axisY, monitor.axisZ))
                    .font(.system(size: 22).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.72))
                    .padding(.top, 28)

                if monitor.active {
                    Text(monitor.rotationZ.map { String(format: "Rotação eixo Z: %.0f°", $0) }
                         ?? "Rotação eixo Z: a aguardar leitura")
                        .font(.system(size: 20))
                        .foregroundStyle(.white.opacity(0.8))
                        .padding(.top, 14)
                }

                Text(String(format: "Inclinação: %.0f°", monitor.inclinationNow))
                    .font(.system(size: 25))
                    .foregroundStyle(.white.opacity(0.8))
                    .padding(.top, 16)

                GeometryReader { geometry in
                    let valor = min(max(monitor.inclinationNow, 0), 180)
                    let posicao = geometry.size.width * valor / 180
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.white.opacity(0.45))
                            .frame(height: 3)
                        Capsule()
                            .fill(turquesa)
                            .frame(width: posicao, height: 3)
                        Circle()
                            .fill(turquesa)
                            .frame(width: 15, height: 15)
                            .offset(x: max(0, min(posicao - 7.5, geometry.size.width - 15)))
                    }
                    .frame(height: 15)
                }
                .frame(height: 15)
                .padding(.top, 15)

                if let referencia = monitor.inclinationFromRef {
                    Text(String(format: "Desde a referência: %.0f°", referencia))
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(referencia > 40 ? .red : verde)
                        .padding(.top, 14)
                }

                if monitor.active {
                    Button("Parar monitorização") {
                        monitor.stop()
                    }
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.7))
                    .padding(.top, 28)
                }

                Text(monitor.lastEvent)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.7))
                    .padding(.top, 15)

                Text("No iPhone, o SMS é confirmado no ecrã de Mensagens. Teste GPS, Shelly e alertas no aparelho antes de depender deles.")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.55))
                    .multilineTextAlignment(.center)
                    .padding(.top, 12)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 35)
        }
        .background(fundo.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .scrollDismissesKeyboard(.interactively)
        .onAppear {
            nome = nomeGuardado == "Telemóvel" ? "" : nomeGuardado
            contacto = contactoGuardado
            intervalo = "\(monitor.intervalMs)"
        }
        .sheet(isPresented: $mostrarSMS) {
            MessageComposer(recipient: contactoGuardado,
                            body: monitor.helpMessage(name: nomeGuardado))
        }
        .confirmationDialog("Esquecer botões Shelly?",
                            isPresented: $confirmarEsquecer) {
            Button("Esquecer botões", role: .destructive) {
                monitor.forgetButtons()
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Os botões deixam de enviar alertas até serem associados novamente.")
        }
    }

    private func linha<CampoView: View>(
        _ titulo: String,
        acao: @escaping () -> Void,
        @ViewBuilder campo: () -> CampoView
    ) -> some View {
        HStack(spacing: 12) {
            campo()
                .frame(maxWidth: .infinity)
            Button {
                campoAtivo = nil
                acao()
            } label: {
                Text(titulo)
                    .font(.system(size: 16, weight: .semibold))
                    .tracking(1)
                    .foregroundStyle(.black)
                    .frame(width: 122, height: 43)
                    .background(roxo, in: RoundedRectangle(cornerRadius: 5))
            }
            .buttonStyle(.plain)
        }
    }
}

private struct CampoSublinhado: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(.system(size: 18))
            .foregroundStyle(.white)
            .padding(.vertical, 8)
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(Color.white.opacity(0.65))
                    .frame(height: 1)
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
