-- ============================================================================
-- O primeiro plano pago: assinatura do empregador.
--
-- Especificação em `docs/plano-starter.md`.
--
-- **Tabela e não coluna em `employers`.** Uma assinatura tem ciclo de vida --
-- começa, vence, é cancelada, é renovada -- e tem histórico que importa: quem
-- assinou quando, quem cancelou, quanto tempo ficou. Uma coluna sobrescreve
-- tudo isso a cada mudança, e a primeira pergunta de negócio depois do
-- primeiro cliente é exatamente a que a coluna não responde.
--
-- **Desenhada para o plano de 39,90 desde já.** Metade da razão de o Starter
-- vir primeiro é que a camada de cobrança serve os dois, e ela só serve duas
-- vezes se nascer pensando nisso:
--
-- * `employer_id` é o dono comercial de hoje. Quando o workspace do documento
--   do marketplace existir, entra uma `organization_id` ao lado e o backfill é
--   de uma linha -- e não uma migração de dados cobrados.
-- * `plan` é texto com check, não enum: acrescentar 'GROWTH' é alterar a
--   constraint, enquanto um tipo enum exige ALTER TYPE e trava em transação.
-- * Assentos e créditos, quando chegarem, são colunas aqui ou tabelas que
--   apontam para `id`. Nada do que já estiver cobrando precisa mudar.
--
-- **Nada aqui fala com provedor de pagamento.** Na largada a liberação é
-- manual, com `provider = 'MANUAL'`, e `expires_at` é posto à mão. Quando o
-- webhook existir ele preenche `provider` e `provider_ref` e passa a mandar na
-- data. A forma da tabela não muda.
-- ============================================================================

create table employer_subscriptions (
    id           bigserial   primary key,
    employer_id  bigint      not null references employers (id) on delete cascade,

    -- 'STARTER' é o único valor vendido hoje. O documento do marketplace
    -- descreve os de cima; eles entram aqui quando forem vendáveis, e não
    -- antes -- um valor que nada consegue gravar é um ramo que nada testa.
    plan         text        not null check (plan in ('STARTER')),
    cycle        text        not null check (cycle in ('MONTHLY', 'ANNUAL')),

    -- ACTIVE é o único estado que concede direito. CANCELLED continua valendo
    -- até `expires_at`: quem pagou o mês tem o mês, e cancelar não é estorno.
    status       text        not null default 'ACTIVE'
                             check (status in ('ACTIVE', 'CANCELLED', 'EXPIRED')),

    -- 'MANUAL' enquanto a liberação for feita à mão. Depois: 'STRIPE' e a
    -- referência da assinatura lá, para conciliar os dois lados.
    provider     text        not null default 'MANUAL',
    provider_ref text,

    started_at   timestamptz not null default now(),
    -- **O direito acaba aqui, e só aqui.** Nenhuma leitura de direito olha
    -- `status` sozinho: uma assinatura ACTIVE cujo `expires_at` passou não
    -- concede nada. Guardar a data em vez de confiar num job que marca EXPIRED
    -- significa que um job que não rodou não libera ninguém de graça.
    expires_at   timestamptz not null,
    cancelled_at timestamptz,

    -- Por que esta linha existe, quando foi liberada à mão. Some quando o
    -- webhook assumir, e até lá é a única trilha de auditoria que há.
    note         text,
    created_at   timestamptz not null default now()
);

-- A pergunta que toda página faz: "este empregador tem plano agora?"
create index employer_subscriptions_live
    on employer_subscriptions (employer_id, expires_at desc);

-- Uma assinatura viva por empregador. Liberar duas vezes à mão é o erro
-- óbvio do processo manual, e ele é barato de tornar impossível.
create unique index employer_subscriptions_one_active
    on employer_subscriptions (employer_id)
    where status = 'ACTIVE';
