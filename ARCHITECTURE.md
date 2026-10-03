# KingdomMinions — model i zakres

## Problem i granica systemu

Serwer nie korzysta z NeoForge, dlatego logika musi działać jako plugin Paper/Purpur. Gracz używa zwykłego klienta Minecraft bez moda. Plugin odpowiada za różdżki, zaznaczenia, encje pomocników, zadania i zapis danych.

## Źródła prawdy

- `data.yml`: aktywne nadania różdżek, UUID, imiona i pozycje pomocników, preferencje, zapamiętana skrzynia, jednorazowe wezwania do właściciela, plecaki oraz bieżące zadanie z postępem.
- Persistent Data Container przedmiotu: identyfikator nadania różdżki.
- Persistent Data Container encji: właściciel i numer pomocnika.
- `events.jsonl`: trwała historia ważnych działań administracyjnych i zleceń.
- Świat Minecraft: aktualny stan bloków i encji.

## Encje domenowe

- Władca: gracz z aktywnym nadaniem.
- Nadanie różdżki: pojedynczy wymienialny token powiązany z UUID władcy.
- Pomocnik: serwerowa encja powiązana z władcą.
- Zaznaczenie: dwa narożniki na jednej płaszczyźnie i kierunek powierzchni.
- Zadanie: trwała kolejka kroków niszczenia lub budowania; wyrównywanie ma trwały kursor kolumn zamiast wielkiej kolejki.
- Dostawa: docelowy kontener albo właściciel.

## Główne procedury

1. Administrator nadaje różdżkę.
2. Gracz wskazuje pierwszy narożnik; plugin pokazuje bieżącą ramkę i wymiary.
3. Drugi narożnik zamyka zaznaczenie i otwiera menu kontekstowe.
4. Wybrany rozkaz jest walidowany, zamieniany na kolejkę albo strumieniowy plan wyrównania i zapisany w danych oraz historii.
5. Pomocnicy dochodzą do celu, równolegle pracują nad osobno przydzielonymi blokami, zapełniają własny plecak i fizycznie wracają do zapamiętanej skrzyni albo właściciela.
6. Dostawa do właściciela jest jednorazowym wezwaniem; po oddaniu łupu pomocnik wraca do zachowanego kroku. Pauza zachowuje kolejkę i łup, anulowanie usuwa kolejkę i kieruje łup do skrzyni, a odesłanie zwraca przechowywane przedmioty.

## MVP

Nadawanie różdżek, czterech pomocników, podążanie całej grupy, ruch do celu, dokładne zaznaczenie ściany/podłogi, tunel, wykop w dół, wyrównanie terenu, brukowe schody, żyła rudy, ograniczone ścinanie, brodawki, fizyczna dostawa, pauza, anulowanie, odesłanie i trwały zapis pracy.

## Świadome ograniczenia

Plugin utrzymuje tylko chunki bieżącego pomocnika i jego aktualnego celu. Nie skanuje całego lasu poza zaznaczeniem. Nie zawiera rytuału XP ani efektu błyskawicy. Zaznaczenie jest wykonywane cząsteczkami widocznymi tylko dla właściciela.
