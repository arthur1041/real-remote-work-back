-- Gerações compradas além das cinco do mês, e de onde saiu cada ranking.
--
-- A decisão de 7 Nov fixou cinco gerações por mês no Growth e previu "pacote
-- de mais cinco por 14,90". Isto é esse pacote, com quatro tamanhos, e a
-- decisão nova do Arthur: comprável a qualquer momento, não só quando as cinco
-- acabam.
--
-- **O saldo continua sendo contado, nunca guardado.** É a regra que a seção
-- C.3 já impunha à cota mensal e a razão dela não mudou: um saldo em coluna é
-- um saldo que diverge do que de fato aconteceu, e divergir aqui é cobrar de
-- alguém uma geração que não houve ou dar de graça as que houve. O saldo de
-- crédito é `soma dos pacotes comprados` menos `rankings pagos com crédito`,
-- e as duas pontas são linhas.
--
-- **Por isso a coluna em `discovery_rankings`.** Sem ela não há como separar
-- "gastou as cinco do mês" de "gastou crédito comprado": a contagem do mês
-- zera na virada e o crédito não, então somar os dois numa conta só faria o
-- crédito evaporar todo primeiro de mês. A coluna diz, por linha, de qual
-- bolso saiu -- escrita no momento em que a geração é pedida, que é o único
-- momento em que a resposta é conhecida.

create table discovery_credit_purchases (
    id           bigserial primary key,
    employer_id  bigint not null references employers(id) on delete cascade,

    -- Quantas gerações este pacote trouxe. O tamanho, não o nome do pacote:
    -- renomear "+35" para outra coisa amanhã não pode mudar o que alguém
    -- comprou ontem.
    generations  integer not null check (generations > 0),

    -- O que foi cobrado, em centavos, na moeda em que foi cobrado. Guardado
    -- porque é a prova do que a pessoa pagou -- o preço na tela é editável e
    -- esta linha não.
    amount_cents integer,
    currency     text,

    /*
     * A sessão do Stripe que originou isto.
     *
     * Única, e é ela que torna o webhook idempotente: o Stripe reentrega
     * eventos, e sem esta restrição uma reentrega daria o pacote duas vezes.
     * Nula só para concessão manual -- suporte, cortesia -- que é feita por
     * uma pessoa e não se repete sozinha.
     */
    provider_ref text,

    note         text,
    created_at   timestamptz not null default now()
);

create unique index discovery_credit_purchases_ref_once
    on discovery_credit_purchases (provider_ref)
 where provider_ref is not null;

create index discovery_credit_purchases_by_employer
    on discovery_credit_purchases (employer_id, created_at);

-- De qual bolso saiu esta geração. MONTHLY é o padrão porque é o que todas as
-- linhas existentes foram.
alter table discovery_rankings
    add column paid_with text not null default 'MONTHLY'
    check (paid_with in ('MONTHLY', 'CREDIT'));

create index discovery_rankings_credit_spend
    on discovery_rankings (employer_id)
 where paid_with = 'CREDIT';
