"""Factual SI definitions, fixed types; no expression evaluation.

Sources: BIPM SI Brochure / prefixes and NIST SP330 section 2. Different
quantities with identical SI dimensions (Hz/Bq, Gy/Sv, rad/sr) remain distinct.
"""
from decimal import Decimal
SI='https://www.nist.gov/pml/special-publication-330/sp-330-section-2'
PREFIXES='https://www.bipm.org/en/measurement-units/si-prefixes'


def extend(units):
    definitions=[('A','current','Ampere'),('mol','amount','Mol'),('cd','luminous_intensity','Candela'),
        ('rad','plane_angle','Radiant'),('sr','solid_angle','Steradiant'),('Hz','frequency','Hertz'),
        ('N','force','Newton'),('Pa','pressure','Pascal'),('J','energy','Joule'),('W','power','Watt'),
        ('C','charge','Coulomb'),('V','voltage','Volt'),('F','capacitance','Farad'),('Ω','resistance','Ohm'),
        ('S','conductance','Siemens'),('Wb','magnetic_flux','Weber'),('T','magnetic_flux_density','Tesla'),
        ('H','inductance','Henry'),('lm','luminous_flux','Lumen'),('lx','illuminance','Lux'),
        ('Bq','activity','Becquerel'),('Gy','absorbed_dose','Gray'),('Sv','equivalent_dose','Sievert'),('kat','catalytic_activity','Katal')]
    for symbol,kind,name in definitions:units.append((symbol,kind,'1','0',[name],SI))
    by_symbol={row[0]:row for row in units}
    prefixes=[('Q','quetta',30),('R','ronna',27),('Y','yotta',24),('Z','zetta',21),('E','exa',18),('P','peta',15),
              ('T','tera',12),('G','giga',9),('M','mega',6),('k','kilo',3),('h','hekto',2),('da','deka',1),
              ('d','dezi',-1),('c','zenti',-2),('m','milli',-3),('μ','mikro',-6),('n','nano',-9),('p','piko',-12),
              ('f','femto',-15),('a','atto',-18),('z','zepto',-21),('y','yokto',-24),('r','ronto',-27),('q','quecto',-30)]
    # Kilogram receives prefixes on gram, never compound prefixes on kg.
    roots=[by_symbol[s] for s in ('m','g','s','K','A','mol','cd')]+[by_symbol[s] for s,_,_ in definitions if s not in ('A','mol','cd')]
    for symbol,dimension,scale,offset,names,url in roots:
        for prefix,name,power in prefixes:
            combined=prefix+symbol;factor=str(Decimal(scale).scaleb(power))
            if combined in by_symbol:
                existing=by_symbol[combined]
                if existing[1]!=dimension or Decimal(existing[2])!=Decimal(factor):raise ValueError('Conflicting SI unit '+combined)
                continue
            row=(combined,dimension,factor,'0',[name+names[0].lower()],PREFIXES)
            units.append(row);by_symbol[combined]=row
    # Common exact non-coherent transforms with explicit quantity types.
    units.extend([('L','volume','0.001','0',['Liter','l'],SI),('mL','volume','0.000001','0',['Milliliter'],SI),
                  ('m3','volume','1','0',['Kubikmeter','m³'],SI),('m2','area','1','0',['Quadratmeter','m²'],SI),
                  ('bar','pressure','100000','0',['Bar'],SI)])
    return units
