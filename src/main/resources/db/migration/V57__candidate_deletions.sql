-- Quem apagou a conta, guardado de propósito.
--
-- Até aqui apagar era apagar: `deleteAccount` zerava todos os campos do perfil,
-- trocava o endereço por `deleted+<id>@deleted.invalid` e não sobrava nada além
-- da casca que segura as candidaturas. Decisão do dono do produto em 31 Out
-- 2026: o histórico passa a ser guardado, para que o admin reconheça quem
-- apagou e voltou a se cadastrar depois.
--
-- **Tabela separada, e não a linha de candidato preservada.** As duas formas
-- dariam o mesmo resultado na tela; só uma delas é segura. Se a linha de
-- `candidates` continuasse com nome e email, toda consulta do site que lê
-- candidatos passaria a depender de lembrar de excluir os apagados -- a busca
-- do recrutador, o digest, o aviso de não lidas, a lista do admin. Uma dessas
-- consultas esquecida é o perfil de alguém que pediu para sair reaparecendo
-- para um empregador. Aqui a lápide continua exatamente como está, nenhuma
-- consulta existente muda de comportamento, e o histórico vive num lugar que
-- ninguém lê por acidente.
--
-- É também o que mantém a promessa de que o endereço fica livre: o email real
-- mora aqui, o índice único de `candidates.email` continua vendo só a lápide, e
-- um cadastro novo com o mesmo endereço nasce vazio, sem herdar nada.
--
-- **Sem `password_hash`.** Guardar a credencial de uma conta que não existe
-- mais não serve a nada e é a única coisa aqui cujo vazamento valeria alguma
-- coisa para alguém.
create table candidate_deletions (
    id              bigserial primary key,
    -- A lápide. `cascade` porque se a linha do candidato algum dia for mesmo
    -- removida, este registro não pode sobreviver a ela.
    candidate_id    bigint not null references candidates(id) on delete cascade,

    -- O que a pessoa era quando saiu.
    email           text not null,
    full_name       text not null,
    country         text,
    headline        text,
    seniority       text,
    skills          text[],
    years_experience integer,
    languages       text[],
    timezone        text,
    linkedin_url    text,
    portfolio_url   text,
    salary_expectation bigint,
    salary_currency text,
    employment_kinds text[],
    available_from  date,

    -- O contexto da saída, que é o que responde "quem está indo embora":
    -- quanto tempo durou, se chegou a confirmar o email, se tinha CV e se
    -- chegou a se candidatar. Contados no momento do apagamento porque depois
    -- dele não há mais como contar.
    email_verified  boolean not null,
    had_cv          boolean not null,
    applications_sent integer not null default 0,
    signed_up_at    timestamptz not null,
    last_login_at   timestamptz,
    deleted_at      timestamptz not null default now()
);

-- A pergunta que motivou a tabela: "esta pessoa que acabou de se cadastrar já
-- tinha apagado antes?". Por `lower(email)`, porque é assim que o cadastro
-- compara endereços.
create index candidate_deletions_email on candidate_deletions (lower(email));

-- E a lista, mais recente primeiro.
create index candidate_deletions_when on candidate_deletions (deleted_at desc);
