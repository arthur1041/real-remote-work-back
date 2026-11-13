-- Sob qual plano a vaga foi publicada, e se ela está pausada por isso.
--
-- **O defeito que isto conserta.** O Starter é vendido como assinatura e era
-- entregue como colocação permanente: um mês pago comprava vitrine para
-- sempre, porque nada nunca fechava uma vaga publicada. Medido em 12 Nov 2026,
-- quando isto deixou de ser teórico: um assinante do Stripe tinha **128
-- submissões, 127 no ar, todas publicadas dentro da janela da assinatura e
-- nenhuma publicada no Free** -- 64% das 197 vagas de empregador abertas no
-- quadro inteiro. Cancelar no dia seguinte deixaria as 127 no ar para sempre.
--
-- A regra que passa a valer: quando o direito acaba, as vagas publicadas no
-- Starter saem do quadro e o que foi publicado no Free fica. Nenhum teto novo
-- é aplicado a quem paga -- o Starter continua sem limite, por decisão do dono
-- do produto, porque até o plano de cima existir quanto mais vaga de
-- empregador entra, melhor.

-- ---------------------------------------------------------------- o carimbo

-- **Gravado uma vez, na aprovação, e nunca recalculado.**
--
-- A alternativa era deduzir no momento do cancelamento, comparando a data da
-- vaga com a janela da assinatura, e ela quebra em quatro situações que já
-- existem nesta base: vaga editada volta para PENDING e é reaprovada depois,
-- possivelmente sob outro plano (é para isso que `content_version` existe);
-- assinaturas em períodos separados; a janela CANCELLED que ainda vale até a
-- data; e qualquer isenção dada à mão. Uma dedução que erra em qualquer um
-- desses casos tira do ar a vaga errada de um cliente pagante.
--
-- Nulo significa "nunca foi aprovada", não "Free": uma submissão recusada ou
-- retirada antes de qualquer aprovação nunca teve plano, e fingir que teve
-- encheria a coluna de respostas inventadas.
alter table employer_postings add column published_plan text;

alter table employer_postings add constraint employer_postings_published_plan_valid
    check (published_plan is null or published_plan in ('FREE', 'STARTER'));

-- ----------------------------------------------------------------- a pausa

-- **Coluna, e não um status novo.** `employer_postings.status` tem cinco
-- valores e nove leitores, e o argumento contra um sexto já está escrito neste
-- repositório: foi exatamente por isso que os rascunhos foram parar em
-- `employer_posting_drafts` em vez de virarem um status `DRAFT` -- cada leitor
-- teria de aprender a ignorar um valor que nunca viu, e o que esquecer vira um
-- defeito silencioso. Aqui o status continua APPROVED, que é a verdade: a vaga
-- foi aprovada e continua aprovada. O que mudou é que ela não está sendo
-- mostrada.
--
-- É também o que faz a conta do teto funcionar sozinha: `postingAllowance`
-- conta APPROVED com `jobs.closed_at is null`, então pausar devolve o slot ao
-- empregador sem uma linha de código a mais.
alter table employer_postings add column paused_at timestamptz;

-- **Por que ela foi pausada, que é o que decide o que volta.**
--
-- Sem isto o retorno da assinatura reativaria tudo que está pausado, inclusive
-- o que o empregador pausou de propósito. O caso é concreto e foi o dono do
-- produto quem o levantou: ele tem x (do Free), y e z (do Starter); cancela, y
-- e z pausam, x fica; então ele pausa x à mão e ativa y. Ao reassinar, z volta
-- sozinha, y já está ativa, e **x tem de continuar pausada** -- foi uma decisão
-- dele, não uma consequência do plano.
--
-- PLAN_LAPSE é o único valor que o retorno da assinatura desfaz.
alter table employer_postings add column pause_reason text;

alter table employer_postings add constraint employer_postings_pause_reason_valid
    check (
        (paused_at is null and pause_reason is null)
        or (paused_at is not null and pause_reason in ('PLAN_LAPSE', 'EMPLOYER'))
    );

-- A varredura roda de hora em hora e pergunta sempre a mesma coisa: quais
-- vagas aprovadas e não pausadas de um empregador sem assinatura viva. Parcial
-- porque a parte interessante é minúscula -- 244 submissões no total hoje,
-- contra 17 mil vagas no quadro.
create index employer_postings_plan_pause
    on employer_postings (employer_id, published_plan)
 where status = 'APPROVED' and paused_at is null;

create index employer_postings_paused
    on employer_postings (employer_id, pause_reason)
 where paused_at is not null;

-- ------------------------------------------------------------- o retroativo

-- **A dedução por data, feita uma vez só, aqui, para quem já existia.**
--
-- O que o corpo da migração acima recusa como regra permanente é aceitável
-- como fato histórico: estas linhas são de antes de a coluna existir, não vão
-- mudar mais, e dá para conferir o resultado contra a produção antes de subir.
-- Daqui para a frente ninguém deduz nada -- a aprovação carimba.
--
-- Conferido em 12 Nov 2026 contra a produção: só o empregador 105 tem
-- submissão dentro de uma janela de assinatura (128 delas, todas de 12 Nov,
-- criadas entre 14:12 e 20:15 UTC). O empregador 31, que tem assinatura ativa
-- do Stripe, não tem vaga nenhuma; o empregador 1 tem 5, todas anteriores à
-- sua assinatura manual e nenhuma no ar. Então isto deve carimbar 128 linhas
-- como STARTER e o resto como FREE.
update employer_postings p
   set published_plan = case
         when exists (
             select 1 from employer_subscriptions s
              where s.employer_id = p.employer_id
                and s.plan = 'STARTER'
                and p.created_at >= s.started_at
                and p.created_at <  s.expires_at)
         then 'STARTER'
         else 'FREE'
       end
 where p.published_plan is null
   and p.status in ('APPROVED', 'REMOVED', 'WITHDRAWN');
