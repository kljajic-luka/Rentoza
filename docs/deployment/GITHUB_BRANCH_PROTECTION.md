# GitHub Branch Protection Preporuke

Ovaj dokument definise preporuke da se sanitizovana javna istorija ne izlozi ponovo i da se kvalitativni standard odrzi tokom razvoja.

## Cilj

- Nema direktnih push-eva na `main`
- Nema force push-a i brisanja grana
- Svaki merge prolazi automatsku bezbednosnu proveru
- Svaka izmena ima makar jednu ljudsku reviziju

## Preporucena pravila za `main`

U GitHub UI:

1. `Settings` -> `Rules` -> `Rulesets` -> `New ruleset`
2. Scope: `Branch`
3. Target branches: `main`
4. Ukljuci sledece kontrole:

- `Require a pull request before merging`
- `Require approvals`: minimum `1`
- `Require review from Code Owners` (ako uvedes CODEOWNERS)
- `Require conversation resolution before merging`
- `Require status checks to pass before merging`
- `Do not allow bypassing the above settings`
- `Block force pushes`
- `Block deletions`

## Obavezni status checks

Dodaj najmanje sledeci check kao obavezan:

- `Gitleaks` iz workflow-a [secret-scan.yml](../../.github/workflows/secret-scan.yml)

Ako kasnije dodas build/test workflow-e, i njih postavi kao obavezne.

## Dodatne bezbednosne postavke

U `Settings` -> `Security`:

- Ukljuci `Secret scanning alerts`
- Ukljuci `Push protection` (ako je dostupno na planu)
- Ukljuci `Dependabot alerts`
- Ukljuci `Dependabot security updates`

## Operativna disciplina

- Ne kreirati long-lived grane sa osetljivim podacima
- Ne push-ovati `.env`, lokalne logove i runtime artefakte
- Sve tajne iskljucivo preko Secret Manager-a i CI secrets
- Ako se incident desi, odmah uraditi:
  - istorijsko ciscenje grana/ref-ova
  - verifikaciju dostupnih ref-ova
  - rotaciju kriticnih kljuceva

## Minimalni merge proces

1. Napravi feature granu
2. Otvori PR
3. Sacekaj da `Secret Scan` prodje
4. Prodji review
5. Merge preko PR-a

Ovaj proces minimizira sansu da se stari ili novi osetljivi podaci ponovo pojave u javnoj istoriji.
