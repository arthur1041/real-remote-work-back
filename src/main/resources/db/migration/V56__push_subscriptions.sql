-- O navegador inscrito para receber aviso de mensagem nova.
--
-- Push web é o único canal que este processo consegue disparar sozinho: é um
-- POST HTTPS assinado para o endpoint que o próprio navegador entrega, sem
-- relay de email no meio. O email continua sendo necessário e continua morando
-- no pipeline -- push só alcança quem está com o navegador aberto e deu
-- permissão, e no iPhone só funciona com o site instalado na tela de início.
-- Ver docs/plano-chat.md.
--
-- **Uma linha por navegador, não por pessoa.** Alguém usa o telefone e o
-- computador, e cada um tem a sua inscrição; e a mesma pessoa pode estar nos
-- dois lados do produto, como candidata e como empregadora, com permissões
-- diferentes em cada aba. Daí a chave ser o endpoint, que é o que o navegador
-- garante ser único.
create table push_subscriptions (
    id           bigserial primary key,
    -- Exatamente um dos dois. Quem é dono da inscrição decide o que ela
    -- recebe: uma conversa avisa o lado que não escreveu.
    employer_id  bigint references employers(id)  on delete cascade,
    candidate_id bigint references candidates(id) on delete cascade,
    endpoint     text not null unique,
    p256dh       text not null,
    auth         text not null,
    created_at   timestamptz not null default now(),
    -- Carimbado quando o endpoint recusa de vez (404/410). A linha é apagada
    -- nesse caso; a coluna existe para o caso em que a recusa é ambígua e a
    -- inscrição merece uma segunda tentativa antes de sumir.
    failed_at    timestamptz,
    constraint push_subscriptions_one_side check (
        (employer_id is not null) <> (candidate_id is not null)
    )
);

create index push_subscriptions_employer  on push_subscriptions (employer_id)
    where employer_id is not null;
create index push_subscriptions_candidate on push_subscriptions (candidate_id)
    where candidate_id is not null;

comment on table push_subscriptions is
    'Um navegador inscrito para aviso de mensagem. Chave é o endpoint, porque '
    'uma pessoa tem mais de um navegador e pode estar nos dois lados.';
