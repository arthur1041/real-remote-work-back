-- ============================================================================
-- Qual empresa está contratando, por vaga e não por conta.
--
-- **O que estava errado.** `resolveDirectCompany` cria uma linha em
-- `companies` por *conta* (`ats_token = 'employer-<id>'`) e põe toda vaga
-- daquele empregador nela. Isso funciona enquanto quem publica é a própria
-- empresa contratante. Deixa de funcionar no instante em que quem publica é
-- um recrutador independente ou uma agência: dez vagas de dez clientes
-- diferentes aparecem para o candidato sob o nome da agência, e a página da
-- empresa junta vagas que não têm nada em comum além de quem as digitou.
--
-- O radio `employers.posts_as`, de V47, já reconhecia que esses dois existem.
-- Ele só consertava o selo. Isto conserta o nome.
--
-- **Não existe opção de não dizer.** A primeira versão tinha um
-- `client_confidential`, para o recrutador sob cláusula de sigilo. Saiu antes
-- de chegar a produção, e o raciocínio que o derrubou é o seguinte: se a
-- única alternativa a nomear o cliente é mentir, e mentir contraria os termos
-- e a revisão, então o honesto nomeia e o desonesto é pego -- a saída
-- sigilosa não impede a mentira, legitima a opacidade. Vaga sem contratante
-- nomeado é também o veículo clássico de vaga-fantasma e de coleta de
-- currículo, e a candidatura interna passou a ser grátis para todos, de modo
-- que essa superfície existe de fato.
--
-- O board inteiro existe para que o que o anúncio afirma seja verdade. Quem
-- não pode dizer para quem contrata não publica aqui.
--
-- **Nulo nas vagas que já existem**, e o código cai no nome da conta, que é
-- exatamente o comportamento de hoje. Nada publicado muda de empresa por
-- causa desta migração; muda só o que é perguntado daqui para a frente.
--
-- **O índice único `employers_company` fica.** Ele impede que duas contas
-- apontem para a mesma `companies`, e continua valendo: `employers.company_id`
-- passa a significar "a empresa que esta conta *é*", preenchido só para quem
-- marcou que contrata para si. Agência nenhuma escreve nele.
-- ============================================================================

alter table employer_postings
    add column hiring_company_name    text,
    add column hiring_company_website text;

comment on column employer_postings.hiring_company_name is
    'A empresa contratante desta vaga, que não é necessariamente a do perfil '
    'de quem publicou. Nulo nas vagas anteriores a V49: lê-se a da conta.';

comment on column employer_postings.hiring_company_website is
    'Opcional. Quando presente vira o domain da companies, o que separa duas '
    'empresas de mesmo nome e dá um slug melhor à página.';
