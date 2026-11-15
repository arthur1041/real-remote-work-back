-- A lista de exclusão em `jobs` passa a ter a garantia que a de
-- `employer_postings` tem desde a V30. L.4 do `plano-growth.md`.
--
-- A V30 criou a coluna nas duas tabelas e pôs as duas restrições só numa
-- delas. A que ficou sem é a que **toda consulta pública lê**: a home, a busca,
-- as páginas de país, o feed. E a elegibilidade geográfica é critério
-- eliminatório tanto da triagem quanto da descoberta -- é a única exigência
-- que não se presume de campo vazio --, então ela precisa ser confiável
-- justamente na tabela de onde é lida.
--
-- Duas regras, as mesmas palavras da V30:
--
--   1. Lista de exceção só significa alguma coisa contra um escopo que pode
--      ter buracos. WORLDWIDE com exclusões é a contradição que a V30 descreve;
--      COUNTRY com exclusões é um país menos ele mesmo.
--   2. Escopo que é só buraco não é escopo: WORLDWIDE_EXCEPT sem lista quer
--      dizer "o mundo inteiro exceto nada", que é WORLDWIDE escrito errado.
--
-- **A limpeza vem antes da restrição, e é a parte que precisa de cuidado.**
-- `jobs` é alimentada por ingestão de terceiros, não por um formulário: nada
-- impediu uma linha incoerente de entrar nestes meses. Uma migração que falha
-- é um pipeline que não sobe, então as linhas que não passariam são
-- normalizadas primeiro -- e normalizar aqui é remover dado sem significado,
-- não decidir nada: uma exclusão contra um escopo que não comporta exclusão
-- não era lida por ninguém.

update jobs
   set geo_excluded = null
 where geo_excluded is not null
   and cardinality(geo_excluded) > 0
   and (geo_scope is null or geo_scope not in ('WORLDWIDE_EXCEPT', 'REGION'));

update jobs
   set geo_scope = 'WORLDWIDE'
 where geo_scope = 'WORLDWIDE_EXCEPT'
   and (geo_excluded is null or cardinality(geo_excluded) = 0);

alter table jobs add constraint jobs_excluded_check check (
    geo_excluded is null
    or cardinality(geo_excluded) = 0
    or geo_scope in ('WORLDWIDE_EXCEPT', 'REGION')
);

alter table jobs add constraint jobs_except_needs_list check (
    geo_scope is distinct from 'WORLDWIDE_EXCEPT'
    or (geo_excluded is not null and cardinality(geo_excluded) > 0)
);
