# Versão iPhone — estado da adaptação

A pasta original `my-kotlin-idosos-shelly-blu` não foi alterada. Esta cópia contém:

- `app`: aplicação Android existente, ainda compilável;
- `shared`: módulo Kotlin Multiplatform com lógica BTHome e deteção de queda, já usada pela aplicação Android;
- `iosApp/Sources`: primeira implementação iOS em SwiftUI com ecrã, GPS, acelerómetro, botão Shelly, MQTT e composição manual de SMS;
- `iosApp/project.yml`: definição do projeto para XcodeGen, com a dependência CocoaMQTT.

## No Mac

1. Instale Xcode e XcodeGen.
2. Na pasta `iosApp`, execute `xcodegen generate` e abra `IdososAvalonIOS.xcodeproj` no Xcode.
3. Em Signing & Capabilities, escolha a sua equipa Apple e ative a assinatura automática.
4. Escolha o iPhone ligado ao Mac e carregue em Run.
5. Teste GPS, botão Shelly, MQTT e envio manual de SMS num iPhone real. Corrija quaisquer erros de compilação ou de comportamento encontrados no Xcode.

## Limites atuais

- O código iOS ainda não foi compilado nem executado: este computador é Windows e não tem Xcode.
- O projeto iOS ainda não usa diretamente o framework Kotlin `IdososShared`; a regra de queda e o descodificador BTHome foram reproduzidos em Swift para preparar a primeira compilação. A integração direta do framework pode ser feita no Mac.
- A monitorização continua em segundo plano (ecrã bloqueado) através do modo de localização em segundo plano, até carregar em Parar; com a autorização "Permitir sempre", a app é retomada se o iOS a fechar. Gasta mais bateria e tem de ser validada num iPhone real.
- O botão Shelly usa o identificador Bluetooth fornecido pelo iOS em vez do MAC; pode ser necessário associá-lo novamente.
- O iPhone não permite envio automático de SMS por esta interface. O botão ALERTA abre a composição e a pessoa confirma o envio. Quedas e carregões do Shelly são publicados por MQTT enquanto a ligação está ativa.
- A ligação MQTT usa o mesmo broker TCP sem TLS do Android; rever a segurança antes de utilização real.
- Ainda faltam funções secundárias da versão Android, como os controlos de intervalo e de referência, e a validação do fluxo completo no servidor.

## GitHub a partir do Windows

Depois de colocar esta pasta num repositório GitHub, o workflow `.github/workflows/ios-build.yml` corre num Mac do GitHub: instala XcodeGen, gera o projeto e tenta compilar para o simulador iOS. Isto permite descobrir erros de compilação sem ter um Mac próprio.

O workflow ainda **não** assina a aplicação, não cria um arquivo para iPhone e não envia nada para o TestFlight. Para isso, são necessários Apple Developer Program, App Store Connect, identificador da app, certificados/perfil de assinatura e credenciais guardadas como segredos do GitHub. Configure essas partes apenas quando a compilação iOS estiver a passar. O TestFlight instala a app no iPhone depois de a Apple receber e processar uma versão assinada.