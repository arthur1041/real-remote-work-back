-- A leitura de cada candidatura contra o que a vaga pede.
--
-- O que o Growth vende. A caixa de entrada continua grátis -- ler uma
-- candidatura que a pessoa mandou de propósito é grátis por princípio, e
-- marcar à mão também -- e o que se paga é a máquina: ler o CV, o perfil e as
-- respostas contra os requisitos da vaga e dizer onde cada pessoa está em cada
-- um deles, com a citação do trecho de onde veio a resposta.
--
-- **Três tabelas e não uma.** Os requisitos são da vaga e valem para todas as
-- candidaturas dela; a leitura é de uma candidatura; o veredito é do cruzamento
-- dos dois. Guardar tudo numa linha só por candidatura obrigaria a reescrever
-- cada uma quando a vaga mudasse de texto, e é exatamente isso que não pode
-- acontecer: mexer na vaga não reescreve em silêncio o que já foi lido.

-- Os requisitos de uma vaga, como o modelo os leu da descrição que o employer
-- escreveu.
--
-- Presos a `content_version` -- a coluna que `employer_postings` já mantém e
-- incrementa a cada edição -- e não só a `posting_id`. É o versionamento que o
-- plano pede: editar a vaga cria um conjunto novo, o antigo continua existindo,
-- e as leituras já feitas continuam apontando para o que foi realmente lido.
-- Reavaliar vira uma ação pedida, nunca um efeito colateral.
create table posting_requirements (
    id              bigserial primary key,
    posting_id      bigint not null references employer_postings(id) on delete cascade,
    content_version integer not null,

    -- A ordem em que o modelo os listou, que é a ordem em que a descrição
    -- falava deles -- e portanto a ordem em que o employer espera lê-los.
    position        integer not null,

    -- O requisito em uma linha, nas palavras da vaga: "5+ anos em backend",
    -- "inglês fluente", "sobreposição com 09:00-13:00 CET".
    text            text not null,

    -- Se não atender este elimina de verdade. O modelo separa o que a vaga
    -- escreveu como obrigatório do que ela escreveu como desejável, e é essa
    -- diferença que faz a nota significar alguma coisa: dez requisitos
    -- desejáveis atendidos não compensam um essencial que não.
    essential       boolean not null default false,

    -- De onde sai a resposta: do texto, lido pelo modelo, ou de um campo que
    -- as duas pontas já preenchem. Onde há campo, o campo vence -- custa zero,
    -- é conferível por consulta, e impede o modelo de contradizer um fato que
    -- a pessoa declarou. `field_code` diz qual: EMPLOYMENT_KIND, COUNTRY,
    -- SALARY.
    source          text not null default 'MODEL'
                    check (source in ('MODEL', 'FIELD')),
    field_code      text,

    created_at      timestamptz not null default now()
);

create unique index posting_requirements_one
    on posting_requirements (posting_id, content_version, position);

-- Uma leitura: uma candidatura contra uma versão dos requisitos.
create table application_screenings (
    id              bigserial primary key,
    application_id  bigint not null references job_applications(id) on delete cascade,
    content_version integer not null,

    /*
     * A nota de 0 a 10, calculada daqui e não pedida ao modelo.
     *
     * Decisão do dono do produto em 1 Nov 2026, corrigindo o A.2 do
     * `plano-growth.md`, que dizia que não haveria nota nenhuma. Há: ela é o
     * que faz a lista abrir pela melhor aderência, que é o valor inteiro da
     * funcionalidade. O que não há é nota *dada pelo modelo* -- um número que
     * ele inventa não é comparável entre dois CVs nem entre duas leituras do
     * mesmo CV, e não se pode abrir para ver de onde veio. Esta sai dos
     * vereditos por uma fórmula que mora no código, então ela é reproduzível,
     * explicável termo a termo, e muda quando a regra muda e não quando o
     * modelo acorda diferente.
     */
    score           numeric(3,1),

    -- A frase curta que o employer lê antes de abrir qualquer coisa. Esta sim
    -- é do modelo, porque é julgamento em prosa e não aritmética: "Forte em
    -- backend e no fuso; nada no CV sobre Kafka."
    summary         text,

    /*
     * Como a leitura terminou, e a direção da falha é escolhida de propósito.
     *
     * Modelo fora do ar, resposta malformada, CV ilegível: a candidatura chega
     * SEM análise, com o motivo anotado, e o employer a lê como lia antes.
     * Nunca "descartada", nunca "assumiu-se que não atende". É o princípio 2
     * do plano, e `failure` existe para que o motivo seja dizível na tela em
     * vez de virar um silêncio.
     */
    state           text not null default 'PENDING'
                    check (state in ('PENDING', 'OK', 'FAILED', 'SKIPPED')),
    failure         text,

    /*
     * Quantas vezes se tentou ler esta candidatura.
     *
     * A coluna que impede o defeito que a primeira versão desta tabela tinha:
     * o índice único abaixo garante uma leitura por candidatura por versão, e o
     * leitor reivindicava a linha com `on conflict do nothing`. O efeito é que
     * uma linha `FAILED` bloqueava aquela candidatura para sempre -- um HTTP
     * 500 do Vertex, um timeout, um blip de rede, e aquela pessoa nunca mais
     * era lida, porque a fila passava a enxergá-la como trabalho já feito.
     *
     * Medido em 1 Nov 2026 no banco local: 2 das 11 leituras falharam, as duas
     * do mesmo candidato, e as duas voltaram em meio segundo contra os ~2,3s de
     * uma leitura de verdade -- a chamada errando, não o modelo reprovando. O
     * employer Growth via dois perfis sem nota e nada no produto consertava.
     *
     * Com o contador, `FAILED` volta para a fila com espera crescente, e um
     * teto de tentativas existe para que o que falha sempre -- um CV corrompido,
     * uma vaga cujo texto o modelo recusa -- pare de custar uma chamada por
     * passagem em vez de insistir para sempre. O mesmo contador cobre a linha
     * `PENDING` abandonada: se o processo morre entre reivindicar e responder,
     * a linha ficava `PENDING` eternamente e a tela dizia "sendo lida agora"
     * para uma leitura que ninguém estava fazendo.
     */
    attempts        integer not null default 0,

    -- Qual modelo leu, porque a resposta de hoje e a de daqui a seis meses não
    -- vêm do mesmo lugar e a diferença precisa ser atribuível.
    model           text,
    -- O que a leitura custou, em tokens, para que o teto diário seja medido e
    -- não estimado.
    tokens_in       integer,
    tokens_out      integer,

    requested_at    timestamptz not null default now(),
    evaluated_at    timestamptz
);

-- Uma leitura por candidatura por versão de requisitos, e não mais. Reavaliar
-- a mesma versão é uma ação explícita que apaga a linha antes de pedir outra.
-- Tentar de novo depois de uma falha não é reavaliar: é a mesma leitura, na
-- mesma linha, com `attempts` somando -- ver o comentário daquela coluna.
create unique index application_screenings_once
    on application_screenings (application_id, content_version);

-- A fila que o pipeline varre.
create index application_screenings_pending
    on application_screenings (requested_at) where state = 'PENDING';

-- O veredito de um requisito dentro de uma leitura.
create table application_requirement_verdicts (
    id              bigserial primary key,
    screening_id    bigint not null references application_screenings(id) on delete cascade,
    requirement_id  bigint not null references posting_requirements(id) on delete cascade,

    /*
     * Três valores, e o terceiro é a razão de existirem três.
     *
     * UNKNOWN nunca é tratado como UNMET. Um CV que não fala de uma coisa não
     * é um CV que prova a ausência dela, e tratar os dois como iguais elimina
     * um bom candidato por omissão -- o modo de falhar mais fácil de não
     * perceber e o mais caro. Princípio 3 do plano.
     */
    verdict         text not null
                    check (verdict in ('MET', 'UNMET', 'UNKNOWN')),

    /*
     * O trecho do CV, do perfil ou da resposta em que o veredito se apoia.
     *
     * Obrigatório para MET e UNMET: sem citação o veredito é rebaixado a
     * UNKNOWN antes de ser gravado. É o que impede a leitura de ser um chute
     * com cara de certeza, e é o que a torna defensável diante do employer e
     * diante do candidato.
     */
    basis           text,
    -- Onde o trecho foi achado, para a tela poder dizer "no CV" ou "no que ele
    -- respondeu".
    basis_source    text check (basis_source in ('CV', 'PROFILE', 'ANSWER', 'FIELD')),

    /*
     * O employer discordando, em um clique.
     *
     * Não muda a nota nem reescreve o veredito: fica ao lado dele. É o insumo
     * para corrigir o catálogo de requisitos depois, quando houver volume --
     * um requisito que todo mundo contesta está mal extraído, e sem isto
     * gravado não há como saber qual.
     */
    disputed_at     timestamptz
);

create unique index application_requirement_verdicts_once
    on application_requirement_verdicts (screening_id, requirement_id);

-- E o plano que paga por tudo isto passa a ser um valor que a tabela aceita.
--
-- `employer_subscriptions.plan` só admitia 'STARTER', porque era o único plano
-- que existia. Sem esta linha nada pode assinar o Growth: a trava da leitura é
-- uma consulta por `s.plan = 'GROWTH'` e ela nunca encontraria linha nenhuma --
-- o que falha na direção segura, mas falha em silêncio, que é pior do que
-- falhar alto.
alter table employer_subscriptions drop constraint employer_subscriptions_plan_check;
alter table employer_subscriptions
    add constraint employer_subscriptions_plan_check check (plan in ('STARTER', 'GROWTH'));
