-- Quais países cada região contém, para que "LATAM" queira dizer países.
--
-- **O defeito que isto corrige, medido em 18 Nov 2026.** A descoberta de CVs
-- filtra quem pode aparecer para uma vaga pela geografia dela, e lia o
-- `geo_detail` procurando códigos de duas letras. Uma vaga `REGION` guarda a
-- chave da região -- "LATAM", "NORTH_AMERICA", "AFRICA, EU, APAC" -- e então:
-- "LATAM" e "NORTH_AMERICA" não davam país nenhum, e o filtro, que trata
-- "nomeia lugares que não sei ler" como "ninguém", não mostrava ninguém; e
-- "EU" passava como se fosse um país chamado EU. O empregador que quer só
-- LATAM -- que é exatamente quem paga por esta ferramenta -- recebia lista
-- vazia ou errada.
--
-- **Uma tabela, e não uma constante em cada lado.** A web filtra em SQL e o
-- pipeline confere de novo antes de pontuar; duas listas escritas à mão são
-- duas respostas para "o México está na América do Norte?", e a segunda é a
-- que ninguém atualiza. Os nomes das regiões são as chaves que o classificador
-- já grava (`Geography.REGIONS`) e que o formulário oferece (`lib/places.ts`).
--
-- As fronteiras que pedem decisão, e a decisão:
-- * LATAM é a América Latina de fato: México, América Central, América do Sul
--   e o Caribe hispânico, lusófono e francófono. O Caribe anglófono (Jamaica,
--   Trinidad, Barbados...) não é América Latina e está em AMERICAS.
-- * NORTH_AMERICA inclui o México, que é onde ele fica. Uma vaga "North
--   America" que não queira o México pode excluí-lo pelo formulário; o
--   contrário -- incluir à mão quem a tabela deixou de fora -- não existe.
-- * APAC é Ásia do Leste, Sudeste, Sul e Oceania. Ásia Central fica de fora,
--   como fica no uso comercial do termo.
-- * EMEA é a Europa inteira (UE e fora dela), o Oriente Médio, o Norte da
--   África e a África, com a Turquia.

create table region_countries (
    region  text not null,
    country text not null,
    primary key (region, country)
);

create index region_countries_country on region_countries (country);

insert into region_countries (region, country)
select r.region, c.country
  from (values
    ('EU', array['AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT',
                 'LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE']),
    ('NORDICS', array['DK','FI','IS','NO','SE','FO','AX','GL']),
    ('DACH', array['DE','AT','CH']),
    ('BENELUX', array['BE','NL','LU']),
    ('ANZ', array['AU','NZ']),
    ('LATAM', array['MX','GT','BZ','SV','HN','NI','CR','PA','CU','DO','HT','PR',
                    'CO','VE','EC','PE','BO','BR','PY','UY','AR','CL','GY','SR','GF',
                    'GP','MQ','BL','MF']),
    ('NORTH_AMERICA', array['US','CA','MX','PM','BM','GL']),
    ('MENA', array['DZ','BH','EG','IR','IQ','IL','JO','KW','LB','LY','MA','OM','PS','QA',
                   'SA','SY','TN','AE','YE','EH']),
    ('AFRICA', array['DZ','AO','BJ','BW','BF','BI','CV','CM','CF','TD','KM','CG','CD','CI',
                     'DJ','EG','GQ','ER','SZ','ET','GA','GM','GH','GN','GW','KE','LS','LR',
                     'LY','MG','MW','ML','MR','MU','YT','MA','MZ','NA','NE','NG','RE','RW',
                     'SH','ST','SN','SC','SL','SO','ZA','SS','SD','TZ','TG','TN','UG','EH',
                     'ZM','ZW']),
    ('APAC', array['AU','NZ','CN','HK','MO','TW','JP','KR','KP','MN','BN','KH','ID','LA',
                   'MY','MM','PH','SG','TH','TL','VN','IN','PK','BD','LK','NP','BT','MV',
                   'FJ','PG','SB','VU','NC','PF','WS','TO','KI','TV','NR','FM','MH','PW',
                   'CK','NU','GU','MP','AS'])
  ) as r(region, members),
  lateral unnest(r.members) as c(country);

-- A Europa fora da UE, para compor a EMEA sem repetir a lista.
insert into region_countries (region, country)
select 'EMEA', c from unnest(array[
    'AL','AD','AM','AZ','BY','BA','FO','GE','GI','GG','IS','IM','JE','LI','MD','MC','ME',
    'MK','NO','RU','SM','RS','CH','UA','GB','VA','AX','TR','XK'
  ]) as c
union select 'EMEA', country from region_countries where region in ('EU', 'MENA', 'AFRICA')
on conflict do nothing;

-- As Américas: a América Latina, a do Norte e o Caribe que não é latino.
insert into region_countries (region, country)
select 'AMERICAS', c from unnest(array[
    'AG','AI','AW','BB','BS','BQ','CW','DM','GD','JM','KN','KY','LC','MS','SX','TC','TT',
    'VC','VG','VI','FK'
  ]) as c
union select 'AMERICAS', country from region_countries where region in ('LATAM', 'NORTH_AMERICA')
on conflict do nothing;

-- O que a pontuação conferiu e o efeito que teve, para que a posição de um
-- perfil na lista possa ser explicada. Ver `DiscoveryRanker.adjust`.
alter table discovery_ranking_entries add column checks jsonb;
