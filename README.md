# Agiliza Cotação — MVP Android

Aplicativo Android nativo para capturar as quatro tabelas reais do ION Vendas, gerar um Excel de preços, preencher um modelo de cotação por caixa e auxiliar o preenchimento de um portal.

## O que já funciona

- Captura assistida e iniciada pelo usuário via `AccessibilityService`.
- Serviço limitado ao pacote `br.com.ionsistemas.ionvendas`.
- Automação restrita ao fluxo **Novo Pedido > Plano de pagamento > Tabela**.
- Seleção automática dos planos **7, 14, 21 e 28 dias**, sem estimar juros.
- **Dois modos de coleta**: sequencial (7 → 14 → 21 → 28, substituindo a base) ou **uma categoria só**, para atualizar apenas um prazo sem esperar os outros. Uma coleta de prazo único entra na base existente e preserva os preços dos demais prazos.
- **Retomada de coleta interrompida**: o prazo atual, os produtos já percorridos e o total por prazo são gravados junto com o rascunho. Se o aplicativo fechar no meio de uma tabela, a tela inicial oferece **Retomar**, que volta ao topo da lista, pula o que já foi lido e continua do mesmo ponto. Quando não existe arquivo de sessão, o ponto de retomada é deduzido dos preços já salvos — um prazo com menos produtos precificados que o prazo mais completo é um prazo que parou no meio.
- Abertura de cada produto somente para leitura dos campos reais do ION: produto, código, EAN da lista, embalagem, unidade, estoque, `Cx Unit/Tot` e `Pço Tabela`.
- Retorno do detalhe exclusivamente com o botão **Voltar** do Android. A captura não toca em **Salvar**, no botão verde, em preço mínimo/tabela/máximo, quantidade, valor ou menus.
- Rolagem automática até o final de cada um dos quatro prazos, deduplicação e proteção contra captura interrompida/parcial.
- Ao terminar um prazo, o app volta à aba **Cabeçalho**, troca o plano de pagamento e **confirma a troca** antes de ler a próxima tabela. Uma tabela nunca é lida sem que o prazo dela esteja confirmado.
- A lista volta ao topo a cada troca de prazo, para que 14, 21 e 28 dias não comecem no fim da rolagem anterior.
- Produtos com **`Und: CJ`** (conjunto) não entram na base. Eles são reconhecidos já na lista, sem abrir o detalhe.
- O EAN completo é lido no detalhe do produto quando a lista o mostra cortado (`006192058019…`).
- Sobreposição com prazo atual, quantidade lida, total de produtos e comandos de pausar/fechar. Ela só existe durante uma sessão de captura e é removida ao concluir, cancelar ou fechar o Agiliza.
- Exportação `.xlsx` com as abas `Cotação`, `Pendências` e `Metadados`.
- Colunas identificadas com os preços por caixa efetivamente lidos em 7, 14, 21 e 28 dias, unidade de venda, unidades por caixa e estoque.
- Somente produtos com estoque numérico maior ou igual a 3 entram na base; estoque menor, ausente ou ilegível é descartado.
- O preço gravado na base é o **valor unitário** (`Pço Tabela` dividido pelo tamanho confirmado da caixa). É esse valor que vai para o portal.
- Modalidade **Preencher modelo Excel por caixa**: preserva o arquivo original, renomeia exclusivamente o cabeçalho `nome` para `Vila`, cruza nomes de forma conservadora, prioriza a variante comercial `CJ` e grava o `Pço Tabela` diretamente, sem multiplicação adicional. Produtos não encontrados ou ambíguos ficam sem preenchimento.
- O modelo escolhido é validado antes da geração. A tela confirma nome, tamanho, aba e célula do cabeçalho `nome`; uma cópia errada ou já preenchida é recusada sem tocar nas demais colunas.
- Portal dentro de WebView restrito ao **esquema, host e porta** configurados. `https` é o preferido, mas portais internos em `http://IP:porta` (o caso do VR Cotação) são aceitos — a tela avisa, antes de guardar a senha, que a conexão não é criptografada.
- O formulário de login é encontrado pela **estrutura da página**, com âncora no campo de senha visível: funciona com os rótulos **Código / Senha / Entrar** do VR Cotação, que não casam com seletores pensados para e-mail. Os seletores CSS continuam existindo como primeira tentativa.
- Se você já entrou e navegou até a cotação, **Preencher automático** trabalha na página aberta em vez de recarregar o link de login.
- Credenciais cifradas com AES-GCM e chave armazenada no Android Keystore.
- **Preenchimento do portal em segundo plano**: o WebView pertence a um serviço em primeiro plano com `WAKE_LOCK` parcial, então bloquear a tela ou sair do aplicativo não interrompe mais o processo. A tela apenas exibe o WebView do serviço e o devolve ao sair.
- **Painel de andamento** com barra animada, percentual, contagem `X de Y` e **estimativa de tempo restante** medida pelo ritmo real da execução — nada de constante chutada. As quatro etapas (`ABRIR`, `ENTRAR`, `LOCALIZAR`, `PREENCHER`) aparecem como trilha, com a atual destacada.
- Nas fases sem percentual (carregar a página, entrar, localizar a tabela) a barra roda um segmento deslizante e a tela mostra o tempo decorrido, atualizado a cada segundo: uma espera longa nunca se confunde com travamento.
- A notificação carrega o mesmo percentual e a mesma estimativa, para acompanhar com a tela bloqueada.
- As colunas do portal (`Código`, `Código Barra`, `Descrição`, `Custo`) são descobertas pelos títulos da própria tabela, sem depender de seletores CSS fixos. O cabeçalho não é assumido como a primeira linha: a cotação abre com uma faixa de grupo (`LOJA BAZAR GERAL`) acima dos títulos, então as primeiras linhas são avaliadas e vence a que revela mais colunas conhecidas.
- Uma cotação dividida em vários grupos/tabelas é preenchida por inteiro: as linhas recebem um índice global e guardam a própria coluna de custo, e uma tabela de layout que envolva a tabela real é descartada para nenhum produto ser contado duas vezes.
- Havendo mais de uma coluna numérica, `Custo` ganha de `Preço`/`Valor`, desde que tenha campo editável. A conferência do valor gravado entende o formato brasileiro (`1.234,56`).
- Correspondência em camadas: EAN completo → EAN truncado confirmado pelo nome → **código do produto confirmado pelo nome** → nome. Um código igual com nome de outro produto é recusado como coincidência de numeração entre sistemas. Empate ou preço divergente deixa o campo em branco.
- O aplicativo **nunca aciona o botão Salvar** do portal; a revisão e o envio continuam sendo do usuário.

## Desempenho com bases grandes

Uma coleta real chega a ~3.000 produtos por tabela. Três mudanças evitam o travamento:

- O texto bruto lido no ION (`rawCells`) é cortado antes de gravar, o que reduz o JSON em ordens de grandeza.
- O rascunho passa a ser gravado em lotes, e não por produto: regravar a base inteira a cada leitura era um custo quadrático.
- A tela principal lê o disco em segundo plano e usa um resumo pré-calculado, em vez de varrer a lista quatro vezes na thread principal.

## Como usar

1. Instale o APK e abra **Agiliza Cotação**.
2. Toque em **Configurar permissão de captura**, leia o aviso e habilite o serviço.
3. Em cada modalidade, escolha qual base capturada será usada: **7, 14, 21 ou 28 dias**. O fornecedor do modelo Excel é fixo como **Vila Nova**.
4. Em **Nova coleta no ION**, escolha como capturar: **sequencial** (os quatro prazos) ou **somente uma categoria** e qual prazo. Depois toque no botão de iniciar.
5. No ION, siga: **Clientes > qualquer cliente > carrinho > Novo Pedido** e permaneça na aba **Cabeçalho**.
6. Na sobreposição do Agiliza, toque no botão de captura (o rótulo mostra o plano escolhido) e aguarde. Não interaja com o ION durante a execução.
   - Se a coleta anterior tiver sido interrompida, a tela inicial mostra o painel **Coleta interrompida** com o prazo e a quantidade já lida. Toque em **Retomar** para continuar daquele ponto em vez de recomeçar.
7. Volte ao Agiliza, revise as linhas e exporte o Excel.
8. Para a nova modalidade, toque em **Selecionar Excel da cotação** e depois em **Gerar Excel preenchido**.
9. Para o portal, informe o link da tela de login (ex.: `http://177.94.206.10:8081/php/vrcotacao/login.php`), o **código de acesso** e a senha em **Configurar portal**, e escolha a base de preços. Depois toque em **Abrir e preencher portal** e em **Preencher automático**: o app entra sozinho, localiza a tabela e preenche a coluna Custo. Pode bloquear a tela — ele continua. Ao terminar, revise os campos e toque em **Salvar** no próprio portal.

## Compilar

Requisitos: JDK 17 e Android SDK 36.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Limites do MVP

- A detecção de colunas foi calibrada pela tela do portal VR Software 3.17 e o login pela tela **VR Cotação** (`Código`, `Senha`, `Entrar`), a partir de captura de tela. Com o HTML real ou uma conta de homologação dá para confirmar os nomes internos dos campos; até lá, a descoberta é estrutural.
- Um portal em `http://` faz o código e a senha trafegarem em texto pela rede. Use apenas dentro da rede da empresa e prefira `https://` quando existir.
- Portais com paginação, tabela virtual, iframe, CAPTCHA ou MFA exigem um adaptador específico.
- Fabricantes com otimização de bateria agressiva (Xiaomi, Samsung, Motorola) podem encerrar o serviço em segundo plano. Se o preenchimento parar sozinho com a tela bloqueada, libere o Agiliza na lista de aplicativos sem restrição de bateria.
- A automação foi mapeada para os identificadores do ION Vendas 1.26.x. Uma atualização do ION que renomeie telas/campos pode exigir ajuste.
- O preenchimento por nome é deliberadamente conservador: uma descrição duvidosa fica vazia para evitar preço no produto errado.
- O APK atual é de desenvolvimento e assinado com a chave de debug. Para distribuição, crie uma chave de release, política de privacidade e valide autorização da empresa/fornecedor.
- Não desative o Play Protect. Para uso interno, prefira distribuição corporativa gerenciada.

## Segurança e integridade

- O app não acessa o banco privado do Ion e não tenta contornar proteções do Android.
- Rascunhos parciais ficam protegidos e podem ser exportados; a última base útil continua disponível se a nova coleta ainda não tiver item elegível.
- Arquivos XLSX são montados antes da gravação e salvos com truncamento explícito, evitando cauda de ZIP antiga ao substituir um documento existente.
- Backup do aplicativo está desativado.
- Nenhuma senha entra no Excel ou nos logs do aplicativo.
- O envio final da cotação permanece sob confirmação do usuário.
