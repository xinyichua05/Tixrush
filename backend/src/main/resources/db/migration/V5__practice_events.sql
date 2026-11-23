-- Practice events (the "Practice rush" page) must never show up in the real shop:
-- hidden from the event list and from "My tickets".
alter table events add column practice boolean not null default false;

update events set practice = true where name = 'Stellar Eclipse: World Tour 2027';
