"""Generate 50 tiny, authored fixtures. These are NOT a Wikipedia extraction."""
import json
from pathlib import Path

FACTS = [
("Berlin", "Berlin ist die Hauptstadt von Deutschland. Die Spree fließt durch Berlin."),
("Photosynthese", "Bei der Photosynthese nutzen Pflanzen Lichtenergie. Aus Wasser und Kohlenstoffdioxid entstehen energiereiche Stoffe und Sauerstoff."),
("Mond", "Der Mond ist der natürliche Satellit der Erde. Er umkreist die Erde."),
("Wasser", "Wasser besteht aus Wasserstoff und Sauerstoff. Seine chemische Formel lautet H2O."),
("Bonsai", "Bonsai bezeichnet die Gestaltung kleiner Bäume in Gefäßen. Schnitt und Pflege beeinflussen ihre Form."),
("Rhein", "Der Rhein entspringt in den Alpen und mündet in die Nordsee."),
("Donau", "Die Donau fließt durch Wien und mündet in das Schwarze Meer."),
("Alpen", "Die Alpen sind ein Gebirge in Europa. Der Mont Blanc ist ihr höchster Berg."),
("Hamburg", "Hamburg liegt an der Elbe und besitzt einen großen Hafen."),
("München", "München ist die Hauptstadt von Bayern. Die Isar fließt durch München."),
("Sonne", "Die Sonne ist der Stern unseres Sonnensystems. In ihrem Inneren findet Kernfusion statt."),
("Mars", "Mars ist ein Planet im Sonnensystem. Seine Oberfläche erscheint rötlich."),
("Jupiter", "Jupiter ist der größte Planet des Sonnensystems. Er ist ein Gasplanet."),
("Saturn", "Saturn ist ein Gasplanet mit einem auffälligen Ringsystem."),
("Venus", "Venus besitzt eine dichte Atmosphäre aus überwiegend Kohlenstoffdioxid."),
("Merkur", "Merkur ist der sonnennächste Planet des Sonnensystems."),
("Erde", "Die Erde ist der dritte Planet von der Sonne aus. Flüssiges Wasser bedeckt große Teile ihrer Oberfläche."),
("Gravitation", "Gravitation ist die anziehende Wechselwirkung zwischen Massen."),
("Elektron", "Ein Elektron trägt eine negative elektrische Ladung."),
("Proton", "Ein Proton trägt eine positive elektrische Ladung und gehört zum Atomkern."),
("Neutron", "Ein Neutron ist elektrisch neutral und gehört zu den Bausteinen von Atomkernen."),
("Kohlenstoff", "Kohlenstoff hat die Ordnungszahl sechs. Diamant und Graphit bestehen aus Kohlenstoff."),
("Sauerstoff", "Sauerstoff ist ein chemisches Element. Menschen benötigen Sauerstoff für die Zellatmung."),
("Stickstoff", "Stickstoff ist der Hauptbestandteil der Erdatmosphäre."),
("Eisen", "Eisen ist ein Metall und ein wichtiger Bestandteil von Stahl."),
("Gold", "Gold ist ein Edelmetall mit dem chemischen Symbol Au."),
("Silber", "Silber ist ein Metall mit hoher elektrischer Leitfähigkeit."),
("Kupfer", "Kupfer ist ein rötliches Metall und wird für elektrische Leitungen verwendet."),
("Zelle", "Die Zelle ist eine grundlegende Einheit lebender Organismen."),
("DNA", "DNA speichert Erbinformation. Ihre Struktur ist häufig eine Doppelhelix."),
("Herz", "Das Herz pumpt Blut durch den Körper."),
("Lunge", "Die Lunge ermöglicht den Gasaustausch zwischen Luft und Blut."),
("Leber", "Die Leber ist ein Organ mit zentralen Aufgaben im Stoffwechsel."),
("Niere", "Die Niere filtert Blut und reguliert den Wasserhaushalt."),
("Gehirn", "Das Gehirn verarbeitet Sinneseindrücke und steuert viele Körperfunktionen."),
("Eiche", "Eichen sind Laubbäume. Ihre Früchte heißen Eicheln."),
("Buche", "Die Rotbuche ist ein Laubbaum. Ihre Früchte heißen Bucheckern."),
("Fichte", "Fichten sind Nadelbäume mit hängenden Zapfen."),
("Kiefer", "Kiefern sind Nadelbäume. Viele Arten tragen lange Nadeln in Bündeln."),
("Moos", "Moose sind Pflanzen ohne echte Wurzeln. Viele Moose wachsen an feuchten Orten."),
("Biene", "Bienen sammeln Nektar und Pollen und tragen zur Bestäubung bei."),
("Ameise", "Ameisen sind Insekten und leben häufig in Staaten."),
("Schmetterling", "Schmetterlinge entwickeln sich über Raupe und Puppe zum erwachsenen Insekt."),
("Frosch", "Frösche sind Amphibien. Viele Arten entwickeln sich aus Kaulquappen."),
("Forelle", "Forellen sind Fische und leben häufig in kühlen sauerstoffreichen Gewässern."),
("Pinguin", "Pinguine sind flugunfähige Vögel und gute Schwimmer."),
("Fledermaus", "Fledermäuse sind Säugetiere und können aktiv fliegen."),
("Igel", "Igel sind Säugetiere. Ihre Stacheln dienen dem Schutz."),
("Fuchs", "Der Rotfuchs gehört zur Familie der Hunde und ist ein Allesfresser."),
("Wolf", "Wölfe gehören zur Familie der Hunde. Sie leben häufig in Rudeln."),
]


def write(path):
    with open(path, "w", encoding="utf-8") as stream:
        for index, (title, text) in enumerate(FACTS, 1):
            stream.write(json.dumps(dict(id=str(index), title=title,
                                        text="== Überblick ==\n" + text,
                                        source=f"fixture://bonsai/{index}"), ensure_ascii=False) + "\n")


if __name__ == "__main__":
    write(Path(__file__).parent / "data/articles.jsonl")
