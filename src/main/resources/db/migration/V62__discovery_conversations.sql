-- Conversa iniciada pelo employer a partir da descoberta, sem candidatura.
--
-- Até aqui toda conversa nascia de uma candidatura: `application_id` era not
-- null e único, e a regra que isso implementava era boa -- o candidato escreve
-- para quem ele procurou, e o employer responde a quem lhe escreveu. A
-- descoberta inverte o sentido pela primeira vez: o employer encontra alguém
-- que nunca se candidatou e quer falar com essa pessoa.
--
-- Duas decisões ficam gravadas aqui:
--
--   1. `application_id` passa a ser nulo nesse caso, e **não** inventamos uma
--      candidatura falsa para caber no modelo antigo. Uma candidatura é uma
--      coisa que o candidato fez; fabricar uma diria, na caixa de entrada dele
--      e nos números do employer, que ele se candidatou a uma vaga que nunca
--      viu.
--   2. `job_id` continua not null. A conversa é sempre **sobre uma vaga** --
--      é dela que vem o contexto que o candidato precisa para entender por que
--      está sendo procurado, e sem ela a mensagem chega como um e-mail frio.
--
-- O índice parcial é o que impede duas conversas abertas com a mesma pessoa:
-- o único de `application_id` não serve, porque em Postgres vários nulos
-- convivem num índice único.
alter table conversations alter column application_id drop not null;

create unique index conversations_discovery_once
    on conversations (employer_id, candidate_id)
 where application_id is null;
