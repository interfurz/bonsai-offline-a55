import unittest
from rag.chunking import clean


class ScienceConversionTests(unittest.TestCase):
    def test_exponents_units_and_real_template_syntax_keep_numbers(self):
        self.assertEqual('10^{−14} ppm; H_{2}O', clean('10<sup>−14</sup>&nbsp;ppm; H<sub>2</sub>O'))
        self.assertEqual('5,380226(24) eV ≈ 519,11 kJ/mol', clean('{{ZahlExp|5,380226|suffix=(24)|post=eV}} ≈ {{ZahlExp|519,11|post=kJ/mol}}'))
        self.assertEqual('1,2 × 10^{8} m', clean('{{ZahlExp|1,2|8|post=m}}'))

    def test_tables_infoboxes_and_math_are_preserved_navigation_removed(self):
        text = clean('{{Infobox Element|Name=Test|Dichte=10 g/cm<sup>3</sup>|Bild=x.png}}\n{| class="wikitable"\n! Wert !! Einheit\n|-\n| 12 || m\n|}\n<math>E=mc^2</math>{{Navigationsleiste|Störung}}')
        for value in ('Dichte: 10 g/cm^{3}', 'Wert', 'Einheit', '12', '$E=mc^2$'):
            self.assertIn(value, text)
        self.assertNotIn('x.png', text)
        self.assertNotIn('Störung', text)
