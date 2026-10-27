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
-- **`client_confidential` existe porque recrutador sigiloso é caso normal**,
-- não exceção: boa parte dos mandatos de agência proíbe nomear o cliente. Sem
-- uma saída honesta, quem está nessa situação escreve um nome falso ou não
-- publica, e as duas são piores que uma vaga que diz, com todas as letras,
-- que o contratante não foi nomeado. A vaga confidencial continua na página
-- da agência -- é o único lugar verdadeiro para ela -- e a flag é o que
-- permite dizer isso na tela em vez de deixar o candidato achar que a agência
-- é a contratante.
--
-- A mesma coluna entra em `jobs` porque é a vaga publicada que precisa
-- responder isso em toda listagem, e buscar o `employer_postings` por trás de
-- cada card seria uma junção por card para ler um booleano.
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
    add column hiring_company_website text,
    add column client_confidential    boolean not null default false;

alter table jobs
    add column client_confidential boolean not null default false;

comment on column employer_postings.hiring_company_name is
    'A empresa contratante desta vaga, que não é necessariamente a do perfil '
    'de quem publicou. Nulo nas vagas anteriores a V49: lê-se a da conta.';

comment on column employer_postings.hiring_company_website is
    'Opcional. Quando presente vira o domain da companies, o que separa duas '
    'empresas de mesmo nome e dá um slug melhor à página.';

comment on column employer_postings.client_confidential is
    'O anunciante não pode nomear o contratante. A vaga fica na página de '
    'quem publicou, e a tela diz que o contratante não foi nomeado.';

comment on column jobs.client_confidential is
    'Copiado de employer_postings na aprovação, para a listagem responder '
    'sem buscar a submissão por trás de cada card.';
