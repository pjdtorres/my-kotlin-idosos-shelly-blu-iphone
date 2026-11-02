import SwiftUI
import MessageUI

/// Ecrã iPhone com o mesmo aspeto da app Android (my-kotlin-idosos-shelly-blu,
/// res/layout/activity_main.xml): tema Material claro/escuro conforme o iPhone,
/// dois botões fixos no topo e o resto numa lista com scroll, pela mesma ordem.
struct ContentView: View {
    @ObservedObject private var monitor = MonitorController.shared

    @AppStorage("nome") private var nomeGuardado = "Telemóvel"
    @AppStorage("contacto") private var contactoGuardado = ""

    @State private var nome = ""
    @State private var contacto = ""
    @State private var intervalo = ""
    @State private var linhaIntervalo = ""      // aparece depois de "Aplicar"
    @State private var toast = ""
    @State private var mostrarSMS = false
    @State private var textoSMS = ""
    @State private var confirmarEsquecer = false

    private enum Campo: Hashable { case nome, intervalo, contacto }
    @FocusState private var campoAtivo: Campo?

    // ---- Cores iguais às do layout Android ----
    private let verdeApp = Color(hex: 0x2E7D32)     // Ligar aplicação / bateria
    private let vermelho = Color(hex: 0xD32F2F)     // Desligar aplicação / avisos
    private let amareloMqtt = Color(hex: 0xFFEB3B)  // botão MQTT
    private let verdeRef = Color(hex: 0x2E9E5B)     // Definir referência
    private let azulGps = Color(hex: 0x1E88E5)
    private let verdeMorada = Color(hex: 0x006400)
    private let laranja = Color(hex: 0xEF6C00)      // ALERTA!
    private let azulShelly = Color(hex: 0x1565C0)
    private let verdeOk = Color(hex: 0x008800)
    private let vermelhoForte = Color(hex: 0xFF0000)

    var body: some View {
        VStack(spacing: 0) {
            // Controlo geral, sempre no topo (fora do scroll), como no Android
            botaoCheio(monitor.active ? "DESLIGAR APLICAÇÃO" : "LIGAR APLICAÇÃO",
                       fundo: monitor.active ? vermelho : verdeApp, texto: .white) {
                campoAtivo = nil
                if monitor.active { monitor.stop() } else { monitor.start(name: nomeGuardado) }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)

            botaoCheio(textoBotaoMqtt, fundo: amareloMqtt, texto: .black) {
                if monitor.active { monitor.toggleMQTT() }
                else { mostrarToast("Ligue primeiro a aplicação") }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)

            ScrollView {
                VStack(spacing: 0) {
                    linhaCampos

                    Text("Pouse o telemóvel na posição de repouso e carregue no botão")
                        .font(.system(size: 12))
                        .foregroundStyle(TemaMaterial.textoSecundario)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 16)

                    Button {
                        monitor.setReference()
                    } label: {
                        Text("🎯  Definir referência")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(verdeRef)
                            .padding(.horizontal, 20)
                            .frame(minHeight: 40)
                            .overlay { Capsule().stroke(verdeRef, lineWidth: 1) }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)

                    if !monitor.referenceText.isEmpty {
                        Text(monitor.referenceText)
                            .font(.system(size: 12))
                            .foregroundStyle(verdeRef)
                            .padding(.top, 4)
                    }

                    // GPS e morada, por cima do ALERTA!
                    VStack(spacing: 0) {
                        Text(monitor.gpsStatus)
                        if !monitor.gpsDetail.isEmpty { Text(monitor.gpsDetail) }
                        if !monitor.address.isEmpty {
                            Text(monitor.address).foregroundStyle(verdeMorada)
                        }
                    }
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(azulGps)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 16)

                    // ALERTA!: pedido de ajuda (página web + SMS pelo servidor)
                    Button {
                        guard monitor.active else {
                            mostrarToast("Ligue primeiro a aplicação")
                            return
                        }
                        monitor.sendHelpRequest()
                        if !contactoGuardado.isEmpty && MFMessageComposeViewController.canSendText() {
                            textoSMS = monitor.helpMessage(name: nomeGuardado)
                            mostrarSMS = true
                        }
                    } label: {
                        textoBotao("ALERTA!", fundo: laranja, texto: .white, ajustar: true)
                            .padding(.horizontal, 16)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 16)

                    Text(monitor.batteryText.isEmpty ? "🔋 Bateria: -" : "🔋 \(monitor.batteryText)")
                        .font(.system(size: 18))
                        .foregroundStyle(monitor.batteryText.isEmpty ? TemaMaterial.textoSecundario : corBateria)
                        .padding(.top, 8)

                    // Botão Shelly BLU (Bluetooth): estado + Associar / Esquecer
                    linhaShelly
                        .font(.system(size: 15))
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 16)
                        .padding(.top, 12)

                    HStack(spacing: 8) {
                        Button {
                            if monitor.active { monitor.associateButton() }
                            else { mostrarToast("Ligue primeiro a aplicação") }
                        } label: {
                            textoBotao("Associar botão", fundo: TemaMaterial.primaria, texto: TemaMaterial.textoPrimaria,
                                       ajustar: true)
                                .padding(.horizontal, 16)
                        }
                        .buttonStyle(.plain)

                        Button {
                            confirmarEsquecer = true
                        } label: {
                            Text("Esquecer")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(TemaMaterial.primaria)
                                .padding(.horizontal, 8)
                                .frame(height: 36)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.top, 6)

                    Text(monitor.active
                         ? String(format: "X: %.1f   Y: %.1f   Z: %.1f",
                                  monitor.axisX, monitor.axisY, monitor.axisZ)
                         : "X: -   Y: -   Z: -")
                        .font(.system(size: 22).monospacedDigit())
                        .foregroundStyle(TemaMaterial.textoSecundario)
                        .padding(.top, 16)

                    Text(monitor.active ? String(format: "Inclinação: %.0f°", monitor.inclinationNow)
                                        : "Inclinação: -")
                        .font(.system(size: 24))
                        .foregroundStyle(TemaMaterial.textoSecundario)
                        .padding(.top, 8)

                    BarraInclinacao(valor: monitor.inclinationNow)
                        .padding(.horizontal, 16)
                        .padding(.top, 4)

                    textoAcelerometro
                        .font(.system(size: 22))
                        .foregroundStyle(TemaMaterial.textoSecundario)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.top, 4)

                    // Caixa cinzenta do MQTT, como no Android
                    Text(textoMqtt)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(.black)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                        .background(Color(hex: 0xEEEEEE))
                        .padding(.horizontal, 16)
                        .padding(.top, 24)
                }
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(TemaMaterial.fundo.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if !toast.isEmpty {
                Text(toast)
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color(white: 0.2).opacity(0.92), in: Capsule())
                    .padding(.bottom, 40)
                    .transition(.opacity)
            }
        }
        .onAppear {
            nome = nomeGuardado == "Telemóvel" ? "" : nomeGuardado
            contacto = contactoGuardado
            intervalo = "\(monitor.intervalMs)"
        }
        .sheet(isPresented: $mostrarSMS) {
            MessageComposer(recipient: contactoGuardado, body: textoSMS)
        }
        // Queda: abre o SMS já preenchido, como o botão ALERTA!
        .onReceive(monitor.$fallSMS) { mensagem in
            guard let mensagem = mensagem else { return }
            monitor.clearFallSMS()
            mostrarToast("Queda detetada!")
            guard !mostrarSMS, !contactoGuardado.isEmpty,
                  MFMessageComposeViewController.canSendText() else { return }
            textoSMS = mensagem
            mostrarSMS = true
        }
        .confirmationDialog("Esquecer botões Shelly?", isPresented: $confirmarEsquecer) {
            Button("Esquecer botões", role: .destructive) { monitor.forgetButtons() }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Os botões deixam de enviar alertas até serem associados novamente.")
        }
    }

    // MARK: - Linhas Nome / Intervalo / Telemóvel

    private var linhaCampos: some View {
        VStack(spacing: 0) {
            linha("GUARDAR", acao: {
                nomeGuardado = nome.trimmingCharacters(in: .whitespaces).isEmpty ? "Telemóvel" : nome
                monitor.setName(nomeGuardado)
                mostrarToast("Nome guardado")
            }) {
                TextField("Nome da pessoa (ex.: D. Maria)", text: $nome)
                    .textContentType(.name)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .focused($campoAtivo, equals: .nome)
                    .onChange(of: nome) { novo in if novo.count > 40 { nome = String(novo.prefix(40)) } }
                    .modifier(CampoMaterial(focado: campoAtivo == .nome))
            }

            linha("APLICAR", acao: {
                if let ms = Int(intervalo), monitor.applyInterval(ms) {
                    linhaIntervalo = String(format: "Intervalo de leitura: %d ms (%.2f Hz)",
                                            ms, 1000.0 / Double(ms))
                    mostrarToast("Intervalo de leitura: \(ms) ms")
                } else {
                    mostrarToast("Intervalo entre 10 e 1000 ms")
                    intervalo = "\(monitor.intervalMs)"
                }
            }) {
                HStack(spacing: 8) {
                    ViewThatFits(in: .horizontal) {
                        Text("Intervalo de leitura (ms):")
                        Text("Intervalo (ms):")
                    }
                    .font(.system(size: 14))
                    .foregroundStyle(TemaMaterial.textoSecundario)
                    TextField("100", text: $intervalo)
                        .keyboardType(.numberPad)
                        .focused($campoAtivo, equals: .intervalo)
                        .onChange(of: intervalo) { novo in
                            let so = String(novo.filter(\.isNumber).prefix(4))
                            if so != novo { intervalo = so }
                        }
                        .modifier(CampoMaterial(focado: campoAtivo == .intervalo))
                }
            }

            linha("GUARDAR", acao: {
                contactoGuardado = contacto.trimmingCharacters(in: .whitespaces)
                mostrarToast(contactoGuardado.isEmpty ? "Número apagado" : "Número guardado")
            }) {
                TextField("Telemóvel p/ SMS (ex.: 912345678)", text: $contacto)
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    .focused($campoAtivo, equals: .contacto)
                    .onChange(of: contacto) { novo in if novo.count > 20 { contacto = String(novo.prefix(20)) } }
                    .modifier(CampoMaterial(focado: campoAtivo == .contacto))
            }
        }
        .padding(.horizontal, 16)
    }

    /// Campo à esquerda + botão de largura fixa (112 dp no Android) à direita.
    private func linha<CampoView: View>(
        _ titulo: String,
        acao: @escaping () -> Void,
        @ViewBuilder campo: () -> CampoView
    ) -> some View {
        HStack(spacing: 8) {
            campo().frame(maxWidth: .infinity)
            Button {
                campoAtivo = nil
                acao()
            } label: {
                textoBotao(titulo, fundo: TemaMaterial.primaria, texto: TemaMaterial.textoPrimaria)
                    .frame(width: 112)
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Botões no estilo Material (cantos de 4 dp, 36 dp de altura)

    private func textoBotao(_ titulo: String, fundo: Color, texto: Color,
                            ajustar: Bool = false) -> some View {
        Text(titulo)
            .font(.system(size: 14, weight: .medium))
            .tracking(1.2)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .foregroundStyle(texto)
            .frame(maxWidth: .infinity)
            .frame(height: 36)
            .background(fundo, in: RoundedRectangle(cornerRadius: 4))
            .shadow(color: .black.opacity(0.25), radius: 1.5, y: 1)
            .padding(.vertical, 6)
            .fixedSize(horizontal: ajustar, vertical: false)
    }

    private func botaoCheio(_ titulo: String, fundo: Color, texto: Color,
                            acao: @escaping () -> Void) -> some View {
        Button(action: acao) {
            Text(titulo)
                .font(.system(size: 14, weight: .medium))
                .tracking(1.2)
                .foregroundStyle(texto)
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .background(fundo, in: RoundedRectangle(cornerRadius: 4))
                .shadow(color: .black.opacity(0.25), radius: 1.5, y: 1)
                .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }

    private var textoBotaoMqtt: String {
        if monitor.mqttStatus.hasPrefix("MQTT a ligar") { return "MQTT A LIGAR…" }
        return monitor.mqttOn ? "DESLIGAR MQTT" : "LIGAR MQTT"
    }

    // MARK: - Textos com cor (iguais ao Html.fromHtml do Android)

    /// "Bateria: 78%" a vermelho com 20% ou menos (e sem estar a carregar).
    private var corBateria: Color {
        let digitos = monitor.batteryText.filter(\.isNumber)
        let carregar = monitor.batteryText.contains("a carregar")
        if let p = Int(digitos), p <= 20, !carregar { return vermelho }
        return verdeApp
    }

    /// "🔘 Shelly: ..." com as barras de sinal coloridas antes do "-44 dBm".
    private var linhaShelly: Text {
        var estado = monitor.shellyText == "Shelly: sem eventos" ? "Shelly: -" : monitor.shellyText
        let bt = monitor.bluetoothStatus
        if monitor.active && !bt.isEmpty && bt != "À escuta do botão Shelly" { estado += "\n" + bt }
        let problema = ["ligue", "falta", "erro", "encriptação", "não tem"]
            .contains { estado.lowercased().contains($0) }
        let cor = problema ? vermelho : azulShelly
        let texto = "🔘 Botão \(estado)"

        guard let r = texto.range(of: #"-?\d+ dBm"#, options: .regularExpression),
              let rssi = Int(texto[r].split(separator: " ")[0]) else {
            return Text(texto).foregroundColor(cor)
        }
        let n = rssi >= -60 ? 4 : rssi >= -70 ? 3 : rssi >= -80 ? 2 : rssi >= -90 ? 1 : 0
        let corBarras = n >= 3 ? verdeApp : n == 2 ? Color(hex: 0xF9A825) : vermelho
        var barras = Text("")
        for (i, b) in ["▂", "▄", "▆", "█"].enumerated() {
            barras = barras + Text(b).foregroundColor(i < n ? corBarras : Color(hex: 0xBDBDBD))
        }
        let antes = String(texto[..<r.lowerBound])
        let depois = " " + String(texto[r.lowerBound...])
        return Text(antes).foregroundColor(cor) + barras + Text(depois).foregroundColor(cor)
    }

    /// Desde a referência / rotação / horizontal-inclinado / intervalo.
    private var textoAcelerometro: Text {
        guard monitor.active else { return Text("Inclinação: -") }
        var t = Text("")
        if let ref = monitor.inclinationFromRef {
            t = ref > 40
                ? Text(String(format: "Desde a referência: %.0f° (SMS)\n", ref)).bold().foregroundColor(vermelhoForte)
                : Text(String(format: "Desde a referência: %.0f°\n", ref)).foregroundColor(verdeOk)
        }
        if let z = monitor.rotationZ {
            t = t + Text(String(format: "Rotação eixo Z: %.0f°\n", z))
        }
        let inc = monitor.inclinationNow
        t = t + ((inc < 15 || inc > 165)
                 ? Text("Telemóvel na horizontal").bold().foregroundColor(vermelhoForte)
                 : Text("Telemóvel inclinado").foregroundColor(verdeOk))
        if !linhaIntervalo.isEmpty {
            t = t + Text("\n" + linhaIntervalo).foregroundColor(verdeOk)
        }
        return t
    }

    private var textoMqtt: String {
        var s = monitor.mqttStatus.hasPrefix("MQTT") ? monitor.mqttStatus : "MQTT: \(monitor.mqttStatus)"
        if monitor.lastEvent != "Sem eventos" { s += "\n\n\(monitor.lastEvent)" }
        return s
    }

    private func mostrarToast(_ texto: String) {
        withAnimation { toast = texto }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            if toast == texto { withAnimation { toast = "" } }
        }
    }
}

// MARK: - Peças de aspeto Material

/// Cores do tema Material por omissão (Theme.MaterialComponents.DayNight),
/// claras ou escuras conforme o iPhone.
private enum TemaMaterial {
    static let primaria = Color(uiColor: UIColor { t in
        t.userInterfaceStyle == .dark ? UIColor(hex: 0xBB86FC) : UIColor(hex: 0x6200EE)
    })
    static let textoPrimaria = Color(uiColor: UIColor { t in
        t.userInterfaceStyle == .dark ? .black : .white
    })
    /// colorSecondary: a SeekBar do Android usa esta cor (turquesa)
    static let secundaria = Color(uiColor: UIColor { t in
        t.userInterfaceStyle == .dark ? UIColor(hex: 0x03DAC5) : UIColor(hex: 0x018786)
    })
    /// Fundo do tema Material escuro (#121212) ou branco
    static let fundo = Color(uiColor: UIColor { t in
        t.userInterfaceStyle == .dark ? UIColor(hex: 0x121212) : .white
    })
    /// Cor por omissão das TextView (textColorSecondary): cinzento
    static let textoSecundario = Color(uiColor: UIColor { t in
        t.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.70) : UIColor(white: 0, alpha: 0.54)
    })
}

/// EditText Material: texto com uma linha por baixo, que fica roxa quando está ativo.
private struct CampoMaterial: ViewModifier {
    let focado: Bool
    func body(content: Content) -> some View {
        content
            .font(.system(size: 18))
            .padding(.vertical, 10)
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(focado ? TemaMaterial.primaria : Color.secondary)
                    .frame(height: focado ? 2 : 1)
            }
    }
}

/// SeekBar do Android (0 a 180°), só de leitura.
private struct BarraInclinacao: View {
    let valor: Double
    var body: some View {
        GeometryReader { g in
            let v = min(max(valor, 0), 180)
            let x = g.size.width * v / 180
            ZStack(alignment: .leading) {
                Capsule().fill(TemaMaterial.textoSecundario.opacity(0.45)).frame(height: 2)
                Capsule().fill(TemaMaterial.secundaria).frame(width: x, height: 2)
                Circle().fill(TemaMaterial.secundaria).frame(width: 18, height: 18)
                    .offset(x: max(0, min(x - 9, g.size.width - 18)))
            }
            .frame(height: 20)
        }
        .frame(height: 20)
    }
}

private extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
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
