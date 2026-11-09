# Guia do Consigliere: passo a passo

Tudo o que dá para configurar, na ordem em que vale a pena fazer. Os nomes dos menus do Google e do Android
podem mudar um pouco de um celular para outro.

---

## 1. Instalar

1. No celular, abra **https://github.com/trevisollinux/conselheiro-ia/releases/latest** e baixe o arquivo
   `Consigliere-<versão>.apk`.
2. Toque no arquivo baixado. Se o Android pedir, permita **"instalar apps desconhecidos"** para o navegador
   (ou para o app de arquivos).
3. Para atualizar depois, é o mesmo caminho: baixe a versão nova e instale **por cima**. Nada se perde.
4. No primeiro uso, as boas-vindas pedem a agenda, o Todoist (opcional), a IA (opcional) e colocam o widget
   na tela inicial. Dá para pular tudo e fazer depois em ⚙️ Configurações.

## 2. Agenda do celular

- ⚙️ → Essencial → **Agenda do celular** → permitir **ler** e **criar eventos**.
- Lê todas as agendas sincronizadas no aparelho (Google e outras). Não precisa de login.
- Em "Criar eventos em", escolha a agenda onde os compromissos novos devem entrar.

## 3. Todoist (opcional)

1. No computador ou no app do Todoist: **Configurações → Integrações → Desenvolvedor**
   (ou abra **https://app.todoist.com/app/settings/integrations/developer**).
2. Copie o **Token da API**.
3. No Consigliere: ⚙️ → Essencial → **Todoist** → cole o token → **Salvar**.

## 4. Inteligência (IA) — recomendado

Sem IA o app funciona no **modo automático** (regras no celular). Com IA ele entende frases soltas, áudio, fotos
e faz os resumos.

### 4.1 Criar a chave do Gemini

1. Abra **https://aistudio.google.com/apikey** com sua conta Google.
2. **Create API key** (Criar chave de API). Copie a chave (começa com `AIza…`).
3. No Consigliere: ⚙️ → Essencial → **Inteligência** → **Minha IA** → **Gemini** → cole a chave →
   **Salvar e testar**. A mensagem de sucesso mostra os dois modelos usados (principal e leve).

### 4.2 Plano pago (opcional, centavos por mês)

O grátis tem pouca cota e às vezes fica lento ("sobrecarregado"). Para ativar o pago:

1. Em **https://aistudio.google.com/apikey**, ao lado do projeto da chave, toque em **Set up billing / Upgrade**.
2. Cadastre o cartão no Google Cloud Billing e vincule ao projeto. A mesma chave continua valendo.
3. Recomendado: em **console.cloud.google.com → Faturamento → Orçamentos e alertas**, crie um alerta (ex.: R$ 10/mês).
4. Para ver o gasto: **https://aistudio.google.com/usage** ou **console.cloud.google.com → Faturamento → Relatórios**
   (o valor aparece com até 1 dia de atraso).

### 4.3 Outra pessoa usando a mesma conta de IA

Crie **outra chave no mesmo projeto** (passo 4.1) e mande para ela. O crédito é o mesmo, mas dá para apagar só
a chave dela depois. **Não use o QR de "Levar para outro aparelho"** para isso: ele copia também o seu Todoist.

## 5. Widgets

1. Segure num espaço vazio da tela inicial → **Widgets** → **Consigliere**.
2. **Meu dia**: lista, conselho do dia e barra "Escreva ou fale". Melhor em 4×2 ou maior.
3. **Conversa rápida**: barra no estilo da busca do Google (mascote, microfone e atalho do app). Melhor em 4×1.
4. Visual: ⚙️ → Personalizar → **Visual do widget** (Noir, Creme, paisagem, obras de arte…).
   Botões de período: ⚙️ → Personalizar → **Períodos da lista**.
5. Para atualizar sozinho em segundo plano: ⚙️ → Avançado → **Bateria** → deixe o app **sem restrições**.

## 6. Ler WhatsApp, Gmail, Telegram e SMS (notificações)

1. ⚙️ → Avançado → **Ler mensagens** → ligue **Detectar compromissos nas notificações** → **Concordo**.
2. Toque em **Abrir acesso a notificações** e ative o **Consigliere** na lista do Android.
3. Grupos do WhatsApp ficam de fora; para incluir, ligue **Incluir grupos do WhatsApp**.
4. **Ver últimas leituras** mostra cada mensagem lida e o que ele decidiu (útil quando algo não virou sugestão).

Limites: só dá para ler o que vira notificação. **Conversa aberta na tela, conversa silenciada e mensagens que
você envia não geram notificação.**

## 7. Ler a caixa de e-mail inteira (Gmail com senha de app)

A senha de app é uma senha especial, só para o Consigliere, que não dá acesso à sua conta Google inteira.

1. A **verificação em duas etapas** precisa estar ligada na conta Google
   (**myaccount.google.com → Segurança → Verificação em duas etapas**).
2. Abra **https://myaccount.google.com/apppasswords** (a opção **não aparece no menu**; só pelo link).
3. Em "Nome do app", digite **Consigliere** → **Criar**.
4. Copie a senha de **16 letras** que aparece (os espaços não importam). Ela só é mostrada uma vez.
5. No Consigliere: ⚙️ → Essencial → **E-mail** → seu endereço Gmail → cole a senha de app → **Conectar**.
6. Pronto: ele olha os e-mails novos a cada atualização (no máximo a cada 10 min). **Verificar agora** força.

Se o Google disser que a opção "não está disponível para sua conta": conta de trabalho/escola ou "Proteção Avançada"
ligada. Outlook e Yahoo também funcionam com a senha de app de cada provedor.

Para desligar: **Desconectar** no app e apague a senha em **myaccount.google.com/apppasswords**.

## 8. Contatos (mensagens, ligações e aniversários)

- ⚙️ → Avançado → **Contatos** → **Permitir acesso aos contatos**.
- Com isso: "avisa a Helena que…" abre direto a conversa dela, "liga pro dentista" já disca e os aniversários
  dos contatos viram aviso às 8h com a mensagem de parabéns pronta.
- Os aniversários vêm do campo "Aniversário" de cada contato (Google Contatos).

## 9. Lembretes por lugar

1. ⚙️ → Avançado → **Lugares** → **Permitir localização** → **Avisar com o app fechado** → no Android, escolha
   **Permitir o tempo todo**.
2. Ensine um lugar estando nele: diga ou escreva **"salva aqui como mercado"**. Endereços completos funcionam
   direto ("quando eu chegar na Av. Brasil 500…").
3. Crie o lembrete: **"quando eu chegar no mercado, me lembra da lista de compras"**.

## 10. Conversa: texto, voz e foto

- **Texto**: aba Conversa ou barra do widget.
- **Voz**: 🎤 no widget, na barra ou na conversa. Usa o reconhecimento de voz do celular; se não houver, grava o
  áudio para a IA.
- **Foto**: ➕ na conversa → **Tirar foto** ou **Escolher da galeria** (lista escrita à mão, convite, boleto…).
  Compartilhar uma imagem com o app também funciona (**Pedir ao Consigliere**).
- Exemplos: "dentista quinta 15h", "lista de compras: leite, pão e ovos", "dar remédio todo dia às 8h",
  "terminei a ração", "avisa a Helena que vou atrasar", "lembra que a senha do Wi-Fi da mãe é…",
  "quando tenho uma tarde livre?", "fiquei de mandar o orçamento pro João até sexta".

## 11. Gravar conversa ou reunião

1. Tela **Hoje** → **🎙️ Gravar conversa ou reunião** (ou ➕ na conversa).
2. Pode apagar a tela; para, toque em **Parar e resumir** (no app ou na notificação). Até 50 minutos.
3. O resumo chega por notificação e na Conversa, com os combinados já criados.

**Ligações não dá para gravar**: o Android reserva o microfone para a ligação. Se uma ligação entrar no meio,
a gravação pausa sozinha e volta depois. Alternativas: gravar com outro aparelho perto (viva-voz) ou, depois de
desligar, contar o combinado pelo 🎤.

## 12. Conversa exportada do WhatsApp

No WhatsApp, abra a conversa → ⋮ → Mais → **Exportar conversa** → **Consigliere**. Ele lê, cria os compromissos que
ainda vão acontecer e manda um resumo por notificação.

## 13. Outras coisas úteis

- **Revisão da semana**: domingo às 19h (⚙️ → Avançado → Revisão da semana para desligar ou ver agora).
- **Resumos da manhã e da noite**: ⚙️ → Personalizar → Resumos automáticos.
- **Tempo**: ⚙️ → Personalizar → Tempo (cidade ou localização).
- **Memória**: ⚙️ → Avançado → Memória (ver e apagar o que ele guardou).
- **Levar para outro aparelho**: ⚙️ → Avançado → Exportar (QR) e Importar. O QR tem seus tokens e chaves: trate como senha.

## 14. Quando algo não funciona

| Problema | O que fazer |
|---|---|
| "Entendendo…" demora | IA grátis sobrecarregada: espere ou ative o pago (4.2). Depois de 8 s dá para fechar e receber por notificação. |
| "IA pausada até…" | Limite da cota grátis. Volta sozinha; o pago resolve. |
| Mensagem do WhatsApp não virou sugestão | Veja **Ver últimas leituras** (seção 6). Se nem aparece, não houve notificação. |
| Widget não atualiza | Bateria → app **sem restrições** (seção 5) e toque no ↻. |
| E-mail "senha incorreta" | Gere outra senha de app (seção 7) e cole sem erros. |
| Ícone ou nome antigo do widget | Remova e adicione o widget de novo. |
