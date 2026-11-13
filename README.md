# Consigliere

Seu conselheiro pessoal no Android: agenda e tarefas juntas num widget, um resumo do que importa no dia
e um assistente que anota por você, por texto ou voz.

📘 **Passo a passo de tudo (instalar, IA, Todoist, e-mail com senha de app, WhatsApp, widgets…): [docs/GUIA.md](docs/GUIA.md)**

O repositório também tem o **[Unusual Stickers](#unusual-stickers-app-de-figurinhas)**, um app separado para criar
figurinhas do WhatsApp sem anúncios.

## O que ele faz

- **Agenda + Todoist numa lista só:** eventos de todas as agendas do celular (Google e outras, lidas localmente,
  sem login) e tarefas do Todoist, com atrasadas em destaque.
- **Conselho do dia:** um resumo curto do que priorizar, com aviso de tempo quando afeta um compromisso
  ("leve guarda-chuva").
- **Conversa por texto ou voz:** "dentista quinta às 15h" vira evento; "lembrar de pagar o MEI amanhã" vira tarefa;
  "terminei a ração da Amelie" conclui; "o que tenho amanhã?" responde. Tudo com **Desfazer**.
  - **Listas:** "lista de compras: leite, pão e ovos" vira uma tarefa com subtarefas no Todoist.
  - **Repetição:** "dar o remédio todo dia às 8h" vira tarefa recorrente.
- **Ações de um toque:** "avisa a Helena que vou atrasar" prepara a mensagem no WhatsApp; "liga pro dentista" disca;
  "como chego no shopping" abre a rota. O Consigliere prepara e você confirma: nada é enviado sozinho.
- **Fotos 📷:** na conversa, tire ou escolha uma foto (lista escrita à mão, print, convite, receita). A IA lê e executa;
  sem IA, o texto é lido no aparelho e várias linhas viram uma lista com subtarefas. Ao compartilhar uma imagem com o app,
  "Pedir ao Consigliere" faz o mesmo.
- **Secretária:**
  - **E-mail (IMAP):** lê a caixa de entrada inteira com uma senha de app; mesmos filtros das notificações.
  - **🎙️ Gravar conversa ou reunião:** grava em primeiro plano (até 50 min) e o Gemini devolve resumo + combinados já criados.
    Durante ligações o Android costuma bloquear o microfone para outros apps.
  - **💬 Responder pelo WhatsApp:** sugestões vêm com a resposta pronta e "Responder" envia pela própria notificação;
    "responde a Helena que sim" também.
  - **🧠 Memória:** "lembra que…" e depois pergunte.
  - **💸 Contas a pagar:** boleto/PIX em e-mails, mensagens e fotos; lista com "Copiar" e lembrete do vencimento.
  - **🗓️ Encaixar horários** ("quando tenho uma tarde livre?") e **combinados** ("fiquei de mandar… até sexta").
  - **Revisão da semana** domingo às 19h. **Conversa exportada do WhatsApp** compartilhada com o app vira resumo e combinados.
- **Aniversários 🎂:** de manhã, aviso dos aniversários dos contatos com "Mandar parabéns" (mensagem pronta no WhatsApp).
- **Lembrete por lugar 📍:** "quando eu chegar no mercado, me lembra da lista de compras"; ensine um lugar com
  "salva aqui como mercado" (endereços funcionam direto). Precisa da localização "o tempo todo".
- **Botões nas notificações:** sugestões têm "Criar" e "Ignorar"; encomendas, "Rastrear"; aniversários, "Mandar parabéns".
- **Encomendas 📦:** códigos de rastreio (ex.: AB123456789BR) achados em mensagens, prints ou na conversa ficam numa
  lista; "Rastrear" copia o código e abre o app ou o site dos Correios.
- **Voz:** usa o reconhecimento de voz do aparelho (o mesmo do teclado), que funciona sem IA. Sem esse serviço,
  grava o áudio e a IA transcreve. Se a IA estiver fora, o áudio fica guardado e é processado depois, com notificação.
- **Mover de dia:** no widget, o **»** joga o item para o dia seguinte (atrasada vem para hoje). No app, na aba
  Agenda, segure o item e solte num dia da barra. Recorrentes e convites de outras pessoas ficam de fora.
- **Sugestões 📌 (opcional):** lê notificações de WhatsApp, Telegram, Gmail, Outlook e SMS e sugere só o que parece
  compromisso **seu**. Grupos ficam de fora por padrão; nada é criado sem você confirmar; sugestões vencidas somem.
- **Compartilhar:** em qualquer app, Compartilhar → Consigliere (texto ou print) abre a tela para criar o evento ou
  a tarefa. Também aparece no menu de texto selecionado.
- **Resumos da manhã e da noite:** no app ("Preparar meu dia"), com notificação opcional.
- **Notion (opcional):** procura datas nas páginas compartilhadas com a integração.
- **Tempo:** Open-Meteo, pela cidade digitada ou pela localização aproximada.

## Widgets

- **Meu dia:** saudação com o mascote, conselho do dia, lista Hoje/Semana/Mês agrupada por dia, ↻ e a barra
  "Escreva ou fale". Fundos: **Noir**, **Creme**, paisagens geradas (mudam com o tempo e a hora), obras de arte
  do Met Museum (domínio público, por estilo), caderno e post-it.
- **Conversa rápida:** barra no estilo da busca do Google: mascote, "Consigliere", microfone e atalho para o app.
  Estreito, vira só o botão do microfone.

O widget atualiza a cada 30 min, quando a agenda muda e pelo ↻ (que também troca a arte).

## Inteligência: dois modos

- **Automático (sem IA):** regras no próprio celular. Entendem "amanhã às 9h", "sexta 14:30", "dia 21/10",
  "8 da noite", listas com ":" e vírgulas… Grátis e nada sai do aparelho.
- **Minha IA:** **Gemini**, **Claude** ou **ChatGPT** com a sua chave. O app chama a API direto do celular,
  sem servidor no meio.
  - **Modelo principal** (conversa, áudio, prints) e **modelo leve** (resumo, briefings, notificações, Notion).
    Vazio = automático: no Gemini, o Flash-Lite mais novo da conta (cota grátis bem maior); no Claude, o Haiku.
  - Limite atingido (429): a IA daquele modelo pausa sozinha e o app usa as regras até a cota voltar.
    Sobrecarga (503) tem uma nova tentativa automática.
  - Chaves: aistudio.google.com (Gemini, tem cota grátis), console.anthropic.com (Claude), platform.openai.com.

## Instalar (APK de teste)

1. Cada push na `main` gera o APK pelo GitHub Actions e publica em
   [Releases → latest](https://github.com/trevisollinux/conselheiro-ia/releases/latest) (`Consigliere-<versão>.apk`).
2. No celular, baixe e instale por cima da versão anterior (permita "instalar apps desconhecidos").
3. No primeiro uso, as boas-vindas guiam agenda, Todoist, IA e colocam o widget na tela inicial.
4. Para o widget atualizar sozinho: Configurações → Avançado → Bateria → "sem restrições".

**Todoist:** Configurações → Integrações → Desenvolvedor → copie o **Token da API**.

**Levar para outro aparelho:** Configurações → Avançado → **Exportar (QR)** num aparelho e **Importar** no outro.
O QR contém o token do Todoist e a chave de IA: trate como senha.

## Play Store

- O build de loja (`bundleRelease`, `.aab`) é assinado com a chave de upload guardada nos segredos do GitHub
  (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`) e sai como
  artefato do workflow. Sem os segredos, o CI gera só o APK de teste.
- A chave em `app/agenda.keystore` é pública (repositório aberto) e serve só para o APK de teste; nunca a use na loja.
- Política de privacidade: [`docs/privacidade.html`](docs/privacidade.html), publicada pelo GitHub Pages
  (Settings → Pages → `main` / `docs`).
- Já atende às exigências da loja: aviso antes de ler notificações e botão ⚑ para denunciar respostas da IA.

## Estrutura

`app/src/main/java/com/trevisolli/agendawidget/`

- `MainActivity.kt`: abas Hoje, Agenda e Conversa; configurações (Essencial, Personalizar, Avançado, Sobre)
- `ChatActivity.kt`, `VoiceActivity.kt`, `ConfirmActivity.kt`: conversa por cima da tela inicial, gravador, revisão de sugestões e compartilhamentos
- `ui/`: tema da marca (`Theme.kt`), boas-vindas (`Onboarding.kt`), aba Agenda com arrastar (`AgendaTab.kt`), conversa (`ChatPanel.kt`)
- `data/`: agenda do celular (`CalendarSource`, `CalendarWriter`), Todoist, mover de dia (`Mover`), tempo, arte do Met, configurações
- `smart/`: IA (`AiClient`, `LightModel`), assistente (`Assistant`), regras em português (`RuleParser`), resumo, briefings,
  leitura de notificações, Notion, voz (`VoiceRecorder`, `VoiceQueue`)
- `widget/`: widgets em Jetpack Glance (`AgendaWidget`, `VoiceWidget`, `ChatBar`), fundos e paisagens, atualização (`RefreshWorker`)
- `tools/fetch_art.py`: baixa a coleção de arte de reserva usada quando o Met está fora do ar

## Unusual Stickers (app de figurinhas)

Um segundo app, no módulo `sticker/` (antes chamado Conselheiro Sticker): cria pacotes de figurinhas para o WhatsApp, **sem anúncios e sem cobrança**.
Tudo fica no celular, sem conta e sem servidor.

- **Pacotes:** de 3 a 30 figurinhas, com nome, autor e ícone gerado da primeira figurinha. **Adicionar ao WhatsApp**
  (ou WhatsApp Business) pela integração oficial.
  O app pergunta ao WhatsApp se o pacote já está lá: o botão vira **✓ No WhatsApp** e, quando você muda o pacote,
  **✨ Atualizar no WhatsApp** (com brilhinhos); a lista de pacotes avisa quais têm mudanças para mandar.
- **Recortar:** **retângulo** com alças nos cantos e lados (livre, quadrado ou círculo),
  ✨ recorte automático do fundo (ML Kit, no aparelho), **laço** (contorne o que fica),
  **borracha** e **pincel** (pinta o que fica; **Pintar do zero** apaga tudo para pintar só um pedaço), com Desfazer.
  **Dois dedos** dão zoom e movem a foto para acertar os detalhes.
- **Ajustar:** mover, zoom e giro com os dedos; borda em volta da figurinha: **Sem borda**, **Fina**, **Média** ou
  **Grossa** (com cor e espessura). Por padrão sai **sem borda**; o app lembra a última escolha para as próximas figurinhas.
- **Texto de meme:** vários textos, arrastar, pinça para tamanho e giro, cor da letra e da borda, CAIXA ALTA.
  Fontes livres embutidas: Anton (parecida com a Impact), Bebas Neue, Archivo Black, Bangers, Luckiest Guy,
  Comic Neue e Permanent Marker. **Importar fonte** aceita qualquer `.ttf`/`.otf` (a Impact é da Microsoft e
  não pode vir no app, mas dá para importar a sua).
- **Só texto:** figurinha com fundo transparente.
- **Animadas (GIF e vídeo):** toque **+ Figurinha → GIF** ou **→ Vídeo** e escolha o
  trecho (até 10 s); a prévia mostra o quadro exato do início ou do fim enquanto você ajusta, e **▶ Ver trecho**
  toca só o pedaço escolhido, em loop. Algo do YouTube ou de outro app: grave a tela do celular e use o vídeo gravado.
  O recorte (retângulo, círculo, laço, borracha), a borda e os textos valem para todos os quadros; a prévia anima
  também na aba Recortar (com ⏸ Pausar para recortar com calma) e em Ajustar e Efeitos. Depois de Cortar seleção, a
  moldura some e aparece o resultado. O app ajusta quadros e qualidade sozinho para caber nos 500 KB do WhatsApp.
- **Efeitos animados:** na aba **Efeitos** do editor, uma foto parada vira animada (dá para combinar, ex.: fogo +
  tremor; também funcionam em GIF e vídeo): 🔥 fogo (com brilho e faíscas), 👾 fogo 16 bits (pixel art), ⚡ raios,
  🫨 tremor, 🔍 zoom dramático, 📺 glitch, 🌈 arco-íris, ✨ brilhos, ❤️ corações, 💫 tontura, ❄️ neve, 🎉 confete,
  😂 chuva de emoji (usa o emoji da figurinha) e 💓 pulsar. Tudo desenhado pelo app, sem imagens de terceiros.
- **Tudo no mesmo pacote:** paradas e animadas convivem num pacote só.
- **Compartilhar:** em qualquer app, Compartilhar uma foto, GIF ou vídeo → Unusual Stickers.
- Toda figurinha guarda a foto original e os textos: **Editar** reabre tudo editável. **Desfazer** (no topo do editor)
  vale para recorte, textos, ajustes e efeitos.
- **Prévia com fundo de conversa:** no editor, o botão no canto da prévia alterna entre xadrez, conversa clara e
  conversa escura, para ver como a figurinha fica no WhatsApp.
- **Backup sem cadastro:** exporta os pacotes num `.zip` e importa de volta (detalhes abaixo).

Formato que o WhatsApp exige (o app cuida sozinho): WebP 512×512 com até 100 KB (animada: WebP animado com até
500 KB e 10 s); ícone PNG 96×96. O WhatsApp não mistura paradas e animadas num pacote: quando um pacote tem alguma
animada, o app manda as paradas como animação de 2 quadros iguais (`w_*.webp`), que aparecem paradas.

### Como usar

1. **Novo pacote** → dê um nome e o autor (o autor fica lembrado para os próximos).
2. **+ Figurinha** → **Foto da galeria** ou **Só texto**.
3. Aba **Recortar**: a foto abre inteira, com a moldura de corte. Arraste os cantos e os lados (ou o meio, para mover),
   escolha **Livre**, **Quadrado** ou **Círculo** e toque **✂️ Cortar seleção** (logo abaixo da imagem; se esquecer, o corte é
   aplicado ao Salvar ou ao trocar de aba). O corte soma com o que já foi recortado. Se preferir, use **✨ Automático** (tira o fundo),
   **Laço** (contorne o que fica), **Borracha** e **Pincel** (com **Pintar do zero** para pintar só o que fica); dois
   dedos dão zoom. **Desfazer** no topo e **Recomeçar** volta à foto inteira.
4. Aba **Ajustar**: arraste e use dois dedos para posicionar; ligue o **contorno de adesivo**; escolha os emojis
   (até 3, usados na busca do WhatsApp).
5. Aba **Texto**: **+ Texto** (o primeiro vai em cima, o segundo embaixo). Toque num texto para selecionar, arraste
   para mover, pinça para tamanho e giro. Troque fonte, cores, borda e CAIXA ALTA.
6. **Salvar**. Com 3 figurinhas ou mais, **Adicionar ao WhatsApp**. Depois de mudar algo, aparece **Atualizar no WhatsApp**.

Na grade do pacote, tocar numa figurinha abre **Editar**, **Emojis**, **Duplicar** (para fazer variações), **Mover** (muda a ordem; a primeira vira o
ícone) e **Apagar**.

### Backup (sem conta)

- **Exportar todos:** na tela inicial, menu **⋮ → Exportar todos os pacotes**. **Um pacote:** dentro dele, botão de
  compartilhar no topo. O Android pergunta onde salvar o `.zip`: Google Drive, Downloads, pendrive…
- **Backup automático:** menu **⋮ → Backup automático → Escolher pasta e ligar**. Toda semana o app salva o `.zip` numa
  pasta do celular (ex.: Documentos) e guarda os 3 últimos; o arquivo fica lá mesmo se o app for desinstalado. O Google
  Drive não deixa apps gravarem sozinhos, então para ele use o Exportar.
- **Importar:** menu **⋮ → Importar backup (.zip)**. Um pacote com o mesmo identificador é substituído pelo do arquivo;
  os outros continuam como estão. Serve também para passar um pacote para outra pessoa que tenha o app.
- O `.zip` leva as figurinhas prontas e os dados para reeditar (foto original, recorte e textos).
- **Backup automático do Android:** também fica ligado e salva as figurinhas prontas na conta Google do celular
  (limite de 25 MB do Android). Os dados de reedição, mais pesados, ficam de fora; para eles, use o Exportar.

### Apoie 💜

O Unusual Stickers é grátis e sem anúncios. Gostou do app? Mande qualquer valor via **Pix**:
**gustavotrevisolli@gmail.com**. (Fica só aqui: a versão da Play Store não pede doação dentro do app,
pelas regras de pagamento do Google Play.)

### Limitações (por enquanto)

- Animadas: até 50 quadros (10 por segundo); trechos longos ficam com menos quadros. O recorte automático não
  funciona em animações (use retângulo ou laço).
- O recorte automático usa o Google Play services: na primeira vez o modelo pode demorar a baixar. Sem ele, o laço
  e a borracha funcionam normalmente.
- O backup é manual (Exportar); o app não envia nada sozinho para a nuvem além do backup automático do Android.

**Instalar:** cada push que mexe em `sticker/` gera o APK no GitHub Actions (workflow *Build Unusual Stickers*,
artefato `unusual-stickers-apk`); na `main`, também sai uma release `sticker-v…` com `UnusualStickers-<versão>.apk`.

**Da versão 1.0 em diante** o pacote Android é `com.trevisolli.unusualstickers` (antes: `com.trevisolli.conselheirosticker`).
Quem tinha uma versão de teste: **Exportar todos os pacotes**, desinstalar, instalar a nova e **Importar backup**.

**Play Store:** o mesmo workflow gera o `.aab` assinado com a chave de upload dos segredos do GitHub (a mesma do
Consigliere), no artefato `unusual-stickers-play-store-aab`. Ficha da loja, ícone 512×512, imagem de destaque e respostas
dos formulários em [`sticker/store/`](sticker/store/ficha-da-loja.md); política de privacidade em
[`docs/unusual-stickers-privacidade.html`](docs/unusual-stickers-privacidade.html) (GitHub Pages).

Código em `sticker/src/main/java/com/trevisolli/conselheirosticker/`: `data/` (pacotes e WebP), `editor/`
(recorte, textos, desenho da figurinha), `ui/` (telas), `whatsapp/` (ContentProvider e intent oficial).
