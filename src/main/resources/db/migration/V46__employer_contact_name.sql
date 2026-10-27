-- ============================================================================
-- O nome da pessoa por trás da conta de empregador.
--
-- **O que faltava.** A tabela tinha `email`, `password_hash`, `company_name` e
-- `website` -- e nenhum ser humano. Todo e-mail de decisão abria com "Hi"
-- seguido de nada, e quando era preciso escrever para alguém sobre uma vaga
-- não havia a quem se dirigir. É o campo mais barato do formulário e o único
-- que o produto já sentia falta.
--
-- **Nulo é permitido, e vai ficar nulo nas contas que já existem.** Não há de
-- onde inferir: `company_name` é a empresa, não a pessoa. Quem já tem conta
-- preenche quando quiser, no My account; os e-mails continuam funcionando sem,
-- caindo para a saudação sem nome que já era o normal.
--
-- **Deliberadamente não é "primeiro nome" e "sobrenome".** Metade do mundo não
-- organiza nome assim, e o único uso disto é escrever "Hi <nome>" e saber com
-- quem se está falando. Um campo só, como a pessoa escreve.
-- ============================================================================

alter table employers add column contact_name text;

comment on column employers.contact_name is
    'Como a pessoa se chama. Nulo nas contas criadas antes de 27 Out 2026.';
