# Bonsai Offline v0.5.2.1

Hotfix für den Absturz des Suchtools auf Android, unter anderem beim Prompt
„Erkläre mir ein schwieriges Thema einfach: Quantenphysik. Benutze das Suche-Tool“
mit Bonsai 2B.

- Behebt `ExceptionInInitializerError` beim ersten Suchaufruf und
  `NoClassDefFoundError: de.bonsai.offline.KnowledgeSearch` bei weiteren Versuchen.
  Ursache war das von Android abgelehnte Regex-Flag `UNICODE_CHARACTER_CLASS`.
- Unicode-Suchbegriffe bleiben erhalten. Wiederholte Suchaufrufe und die
  Übereinstimmung mit dem Python-Fixture-Encoder sind durch Regressionstests
  geprüft; beide ursprünglichen Fehler wurden im Android-Emulator reproduziert.

Version **0.5.2.1**, Versionscode **18**, Paket `de.bonsai.offline.debug`, ARM64.
CPU- und CPU/Vulkan-Release werden mit dem bisherigen Release-Schlüssel signiert.
Das Update kann bei passender Signieridentität über 0.5.2 installiert werden;
die bestehende App muss dafür nicht deinstalliert werden. Wissenspakete und
Modellgewichte werden weiterhin separat importiert.

Die Veröffentlichung enthält Prüfsummen, Signatur- und Updatevergleich sowie
Build- und Testnachweise. Der Suchtest im Android-Emulator ersetzt keine
physische A55-Abnahme oder Prüfung realer Bonsai-2B-Modellantworten.
