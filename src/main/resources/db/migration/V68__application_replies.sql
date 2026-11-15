-- A resposta do employer ao candidato: modelo guardado, envio em lote, registro.
--
-- O B.2 do `plano-growth.md` pede "respostas em modelo, inclusive recusa em
-- lote", e dá a razão: é o que faz o employer voltar ao site em vez de
-- exportar o CSV e responder no Gmail. Mas o buraco que isto fecha é maior do
-- que conveniência.
--
-- As etapas da triagem existem desde a A.4: o employer marca SHORTLISTED ou
-- REJECTED e a pilha se organiza. Do lado do candidato, `stageForCandidate`
-- colapsa as duas em "aberta", de propósito -- anotação que se transmite
-- sozinha é anotação que ninguém escreve. O efeito é que hoje o produto tem um
-- estado de recusa que só existe dentro da cabeça de quem marcou. Quem tem 40
-- candidaturas e 35 fora não escreve 35 e-mails à mão; ou não responde nada,
-- ou leva a relação para fora daqui.
--
-- Então o envio é um ato do employer, com palavras dele, e fica gravado. O
-- princípio 1 do plano continua de pé: nada parte para um candidato por
-- máquina, e não há modelo de linguagem em lugar nenhum deste caminho.

create table employer_reply_templates (
    id          bigserial primary key,
    employer_id bigint not null references employers(id) on delete cascade,

    -- Como o employer chama isto na hora de escolher, não o que o candidato lê.
    name        text not null,
    subject     text not null,
    body        text not null,

    /*
     * O que enviar faz com a candidatura.
     *
     * Guardado no modelo e não escolhido no envio porque é a mesma decisão:
     * quem escreve "não seguimos com a sua candidatura" está marcando
     * REJECTED, e pedir as duas coisas em dois cliques é como as duas
     * divergem -- a caixa de entrada dizendo uma coisa e a pessoa tendo lido
     * outra.
     *
     * Os três valores são os de `EMPLOYER_STAGES`: WITHDRAWN é declaração do
     * candidato e NEW significa "ninguém abriu", nenhum dos dois é coisa que
     * um envio possa afirmar.
     */
    stage       text not null default 'REJECTED'
                check (stage in ('REJECTED', 'SHORTLISTED', 'READ')),

    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index employer_reply_templates_by_employer
    on employer_reply_templates (employer_id, created_at);

-- Um modelo com o mesmo nome duas vezes é uma lista onde não dá para escolher.
create unique index employer_reply_templates_name_once
    on employer_reply_templates (employer_id, lower(name));

create table application_replies (
    id             bigserial primary key,
    application_id bigint not null references job_applications(id) on delete cascade,
    employer_id    bigint not null references employers(id) on delete cascade,

    -- O modelo de onde saiu, se saiu de um. `set null` porque apagar um modelo
    -- não pode apagar a prova do que foi enviado.
    template_id    bigint references employer_reply_templates(id) on delete set null,

    /*
     * O texto como foi enviado, e não uma referência ao modelo.
     *
     * Modelo é coisa que se edita. Se esta tabela guardasse só o `template_id`,
     * a resposta a uma reclamação -- "o que exatamente essa pessoa recebeu?" --
     * seria o texto de hoje, não o de então. O mesmo vale para o endereço: é o
     * que o `replyAddress` resolveu no momento do envio.
     */
    to_address     text not null,
    subject        text not null,
    body           text not null,

    created_at     timestamptz not null default now(),
    sent_at        timestamptz,
    failed_at      timestamptz,
    failure        text,
    attempts       integer not null default 0
);

-- A fila do remetente: parcial, porque o que ela procura é sempre o punhado
-- que ainda não saiu, e não as linhas de todos os meses anteriores.
create index application_replies_unsent
    on application_replies (created_at)
 where sent_at is null and failed_at is null;

create index application_replies_by_application
    on application_replies (application_id, created_at desc);
