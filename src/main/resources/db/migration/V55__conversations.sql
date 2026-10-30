-- A conversa entre quem publicou a vaga e quem se candidatou a ela.
--
-- O botão `Reply` da caixa de candidaturas era um `mailto:`. No instante em
-- que o empregador clicava, a relação saía do produto e nunca voltava: a
-- triagem, as etapas e o funil do plano Growth pressupõem todos que a conversa
-- acontece aqui, e `employers.last_login_at` só é gravado em login com senha,
-- então nem dava para saber que ele tinha voltado. Ver docs/plano-chat.md.
--
-- **Uma conversa por candidatura.** O vínculo não é "empregador fala com
-- candidato", é "empregador fala com quem se candidatou a esta vaga": é isso
-- que torna a conversa esperada pelos dois lados e o que dispensa qualquer
-- controle de quem pode abordar quem. Mandar mensagem para quem não se
-- candidatou é outro produto, e é pago -- o desbloqueio do plano Growth.
create table conversations (
    id              bigserial primary key,
    application_id  bigint not null unique
                    references job_applications(id) on delete cascade,
    -- Desnormalizados de propósito. Toda consulta desta tela filtra por um dos
    -- dois lados, e chegar neles pela candidatura custaria dois joins em
    -- `employer_postings` e `candidates` em cada contagem de não lidas.
    employer_id     bigint not null references employers(id)  on delete cascade,
    candidate_id    bigint not null references candidates(id) on delete cascade,
    job_id          bigint not null references jobs(id)       on delete cascade,
    created_at      timestamptz not null default now(),
    -- Nulo enquanto ninguém falou. A conversa nasce com a candidatura e fica
    -- vazia até alguém escrever, o que é o que permite o botão existir na tela
    -- sem que haja thread para mostrar.
    last_message_at timestamptz,
    employer_read_at  timestamptz,
    candidate_read_at timestamptz,
    -- Bloqueio é por lado: quem bloqueia para de receber, o outro não é
    -- avisado de que foi bloqueado. Denúncia é outra coisa e mora abaixo.
    employer_blocked_at  timestamptz,
    candidate_blocked_at timestamptz
);

-- As duas consultas que existem: a caixa de um lado, ordenada por atividade.
create index conversations_for_employer
    on conversations (employer_id, last_message_at desc nulls last);
create index conversations_for_candidate
    on conversations (candidate_id, last_message_at desc nulls last);

create table conversation_messages (
    id              bigserial primary key,
    conversation_id bigint not null references conversations(id) on delete cascade,
    -- Quem escreveu, não quem é: o id de cada lado já está na conversa, e
    -- guardar o autor como papel é o que deixa a consulta da tela ser uma só.
    sender          text not null check (sender in ('EMPLOYER', 'CANDIDATE')),
    body            text not null,
    created_at      timestamptz not null default now(),
    -- Quando o aviso por email saiu. Nulo é "ainda não avisado", que é o que a
    -- varredura procura; sem isto o agrupamento do aviso teria de ser deduzido
    -- de horários, que é como se manda o mesmo email duas vezes.
    notified_at     timestamptz
);

create index conversation_messages_thread
    on conversation_messages (conversation_id, created_at);
-- Para a varredura de avisos: só as não avisadas, que são sempre poucas.
create index conversation_messages_unnotified
    on conversation_messages (created_at) where notified_at is null;

-- Denúncia. Separada do bloqueio porque responde a outra pergunta: bloquear é
-- "não quero mais falar com esta pessoa", denunciar é "alguém daqui precisa
-- olhar isto". Chat entre estranhos num board de emprego é o canal preferido
-- de golpe de recrutamento, e o lado do candidato carrega CV com dado pessoal.
create table conversation_reports (
    id              bigserial primary key,
    conversation_id bigint not null references conversations(id) on delete cascade,
    reported_by     text not null check (reported_by in ('EMPLOYER', 'CANDIDATE')),
    reason          text,
    created_at      timestamptz not null default now(),
    reviewed_at     timestamptz
);

create index conversation_reports_open
    on conversation_reports (created_at) where reviewed_at is null;

comment on table conversations is
    'Uma conversa por candidatura, entre quem publicou a vaga e quem se '
    'candidatou. Ver docs/plano-chat.md.';
comment on column conversations.last_message_at is
    'Nulo enquanto ninguém escreveu. Também é o relógio da retenção: a '
    'varredura apaga mensagens noventa dias depois da última.';
comment on table conversation_messages is
    'Mensagem é dado pessoal e corre no mesmo prazo do CV: noventa dias.';
