-- ============================================================================
-- Quantas vezes o card da vaga apareceu na tela de alguém.
--
-- **Por que a coluna existe.** `views` conta quem abriu a página da vaga e
-- `apply_clicks` conta quem saiu para se candidatar. Faltava o topo do funil:
-- o empregador via quantos entraram, e nunca quantos *viram* a vaga numa
-- listagem e não entraram. Sem isso, "3 views" é indistinguível de "ninguém
-- viu" e de "500 viram e nenhum se interessou" -- que são dois problemas
-- opostos, um de distribuição e outro de anúncio.
--
-- **É uma coluna e não uma tabela.** A granularidade útil é a mesma que já
-- existe aqui, (vaga, dia), e a contagem é um `+N` na linha que a view já
-- cria. Uma tabela de eventos guardaria uma linha por impressão -- dezenas de
-- milhares por dia para responder exatamente a mesma pergunta.
--
-- **Medido antes de escrever.** Em produção esta tabela tem 5.717 linhas em 11
-- dias, ~520 vagas tocadas por dia. As impressões tocam mais vagas por dia que
-- as views, então a tabela cresce algumas vezes mais rápido -- na casa de um
-- megabyte por mês, com o mesmo índice e o mesmo formato.
--
-- `integer` como as outras duas: dois bilhões de impressões por vaga por dia
-- é um número que este produto não alcança, e mudar depois é um alter table
-- de uma coluna.
--
-- Nada aqui identifica ninguém, que é o que o comentário da tabela promete
-- desde a V17 e continua valendo: a contagem chega agregada por vaga.
-- ============================================================================

alter table job_daily_stats
    add column card_views integer not null default 0;

comment on column job_daily_stats.card_views is
    'Quantas vezes o card desta vaga esteve na tela de alguém numa listagem. '
    'Contado no navegador, metade do card visível; nunca por renderização, '
    'porque a maioria das listagens é servida de cache.';
