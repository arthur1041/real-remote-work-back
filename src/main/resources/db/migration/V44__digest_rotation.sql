-- ============================================================================
-- Registro de que uma vaga paga saiu no digest.
--
-- Ver "Em aberto" em `docs/plano-starter.md`, agora decidido.
--
-- **O problema que levou até aqui.** O digest leva 15 vagas por semana de uma
-- safra de ~160, para 790 inscritos. O bloco vendido fica fora dessas 15 e tem
-- teto próprio de um terço -- porque um bloco pago que cresce até o tamanho do
-- editorial deixa de ser um bloco dentro do e-mail e vira o e-mail.
--
-- O primeiro desenho dava cinco lugares *à edição* e rodava as vagas por eles.
-- Com cem vagas pagas, a do fim da fila esperava vinte semanas: uma feature
-- vendida hoje e entregue na estação seguinte.
--
-- **O que foi feito em vez disso: os cinco lugares são por leitor.** Com 790
-- inscritos a edição tem ~3.950 colocações, toda vaga paga sai em toda edição,
-- e o que muda entre dois leitores é quais cinco eles recebem. A fatia é
-- aritmética a partir do id do leitor e da edição, não sorteio -- a mesma
-- edição enviada duas vezes é a mesma edição, e isso é testável. Medido no
-- teste: com 100 vagas pagas e 790 leitores, cada vaga cai entre 30 e 50
-- caixas de entrada por edição.
--
-- **Então esta coluna não é uma fila, é um recibo.** Ela responde a uma
-- pergunta só, a que o painel do empregador faz: esta vaga saiu, e quando.
-- Carimbada depois do envio, nunca antes, para que uma edição que não foi
-- enviada não afirme ter levado nada.
--
-- **Em `jobs` e não numa tabela de ligação.** É uma data por vaga, lida numa
-- consulta que já passa por `jobs`. Uma tabela (job_id, issue_id) daria o
-- histórico completo de aparições, que ninguém pediu, ao custo de um join na
-- consulta mais quente do envio.
-- ============================================================================

alter table jobs add column digest_featured_at timestamptz;

-- As candidatas do bloco pago: worldwide e abertas. Parcial porque a
-- esmagadora maioria das vagas nunca é candidata -- 24 das 16.658 abertas
-- vieram de empregador, e só parte dessas é de quem paga.
create index jobs_digest_paid
    on jobs (first_seen_at desc)
    where closed_at is null and geo_scope = 'WORLDWIDE';

comment on column jobs.digest_featured_at is
    'Quando esta vaga saiu pela última vez no bloco pago do digest. Nula em tudo que nunca saiu, que é quase tudo.';
