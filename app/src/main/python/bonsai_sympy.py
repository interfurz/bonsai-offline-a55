"""Offline CAS operations over a mathematical AST, never Python eval/exec."""
import ast
import json
import re
import sympy as s

MAX_INPUT = 4096
MAX_NODES = 512
MAX_OUTPUT = 24000
OPERATIONS = frozenset((
    "simplify", "expand", "factor", "cancel", "apart", "trigsimp", "differentiate",
    "integrate", "solve", "solveset", "linsolve", "nonlinsolve", "nsolve", "dsolve",
    "limit", "series", "summation", "product", "numeric", "det", "inverse", "rank",
    "eigenvalues", "transpose", "rref", "gcd", "lcm", "factorint", "isprime",
))
OPTIONS = frozenset(("op", "expr", "var", "vars", "order", "lower", "upper", "point",
                     "direction", "precision", "domain", "assumptions"))
FUNCTIONS = {name: getattr(s, name) for name in (
    "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh", "cosh", "tanh",
    "asinh", "acosh", "atanh", "exp", "log", "sqrt", "Abs", "sign", "floor", "ceiling",
    "conjugate", "re", "im", "arg", "gamma", "loggamma", "erf", "erfc", "zeta", "beta",
    "besselj", "bessely", "airyai", "LambertW", "factorial", "binomial", "Min", "Max",
    "Eq", "Ne", "Lt", "Le", "Gt", "Ge", "And", "Or", "Not", "Piecewise", "Matrix",
    "Integral", "Derivative", "Sum", "Product", "Limit", "Rational",
)}
CONSTANTS = {"pi": s.pi, "E": s.E, "e": s.E, "I": s.I, "oo": s.oo,
             "EulerGamma": s.EulerGamma, "Catalan": s.Catalan, "True": s.true, "False": s.false}
NAME = re.compile(r"[A-Za-z][A-Za-z0-9]{0,31}\Z")
ASSUMPTIONS = frozenset(("real", "positive", "negative", "nonnegative", "nonpositive",
                         "integer", "nonzero", "complex", "finite", "rational"))


class MathInputError(ValueError):
    pass


class Parser:
    def __init__(self, assumptions):
        self.symbols = {}
        if not isinstance(assumptions, dict) or len(assumptions) > 16:
            raise MathInputError("Annahmen müssen ein Objekt mit maximal 16 Symbolen sein.")
        for name, flags in assumptions.items():
            if not NAME.fullmatch(name) or name in CONSTANTS or name in FUNCTIONS:
                raise MathInputError("Ungültiger Symbolname in Annahmen.")
            if not isinstance(flags, dict) or set(flags) - ASSUMPTIONS or any(type(v) is not bool for v in flags.values()):
                raise MathInputError("Unbekannte oder nicht boolesche Annahme.")
            self.symbols[name] = s.Symbol(name, **flags)
        self.nodes = 0

    def symbol(self, name):
        if not NAME.fullmatch(name) or name in FUNCTIONS or name in CONSTANTS:
            raise MathInputError("Ungültiger Variablenname.")
        if name not in self.symbols:
            if len(self.symbols) >= 32:
                raise MathInputError("Höchstens 32 Symbole.")
            self.symbols[name] = s.Symbol(name)
        return self.symbols[name]

    def parse(self, text):
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_INPUT:
            raise MathInputError("Ausdruck: 1–4096 Zeichen.")
        # JSON arrays provide a convenient equation-system format without Python strings.
        if text.lstrip().startswith("["):
            try:
                items = json.loads(text)
            except ValueError:
                items = None
            if isinstance(items, list) and items and all(isinstance(v, str) for v in items):
                if len(items) > 32:
                    raise MathInputError("Höchstens 32 Gleichungen.")
                return [self.parse(item) for item in items]
        if "=" in text and not any(c in text for c in ("==", "<=", ">=", "!=", "Eq(")):
            if text.count("=") != 1:
                raise MathInputError("Eine Gleichung benötigt genau ein Gleichheitszeichen.")
            left, right = text.split("=")
            return s.Eq(self.parse(left), self.parse(right), evaluate=False)
        try:
            tree = ast.parse(text.strip().replace("^", "**"), mode="eval")
        except (SyntaxError, RecursionError) as exc:
            raise MathInputError("Ungültiger mathematischer Ausdruck.") from exc
        if sum(1 for _ in ast.walk(tree)) > MAX_NODES:
            raise MathInputError("Ausdruck zu komplex.")
        return self.visit(tree.body, 0)

    def visit(self, node, depth):
        self.nodes += 1
        if self.nodes > MAX_NODES or depth > 40:
            raise MathInputError("Ausdruck zu komplex oder zu tief verschachtelt.")
        def child(value):
            return self.visit(value, depth + 1)
        if isinstance(node, ast.Constant):
            if type(node.value) is bool:
                return s.true if node.value else s.false
            if type(node.value) is int:
                if node.value.bit_length() > 4096:
                    raise MathInputError("Ganzzahl zu groß.")
                return s.Integer(node.value)
            if type(node.value) is float:
                value = s.Rational(str(node.value))
                if max(abs(value.p).bit_length(), value.q.bit_length()) > 4096:
                    raise MathInputError("Dezimalzahl zu groß.")
                return value
            raise MathInputError("Nur Zahlen, keine Strings oder Objekte im Ausdruck.")
        if isinstance(node, ast.Name):
            return CONSTANTS[node.id] if node.id in CONSTANTS else self.symbol(node.id)
        if isinstance(node, (ast.List, ast.Tuple)):
            if len(node.elts) > 64:
                raise MathInputError("Liste oder Matrix zu groß.")
            return [child(value) for value in node.elts] if isinstance(node, ast.List) else tuple(child(value) for value in node.elts)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            value = child(node.operand)
            return -value if isinstance(node.op, ast.USub) else value
        if isinstance(node, ast.BinOp):
            left, right = child(node.left), child(node.right)
            if not isinstance(left, (s.Expr, s.MatrixBase)) or not isinstance(right, (s.Expr, s.MatrixBase)):
                raise MathInputError("Ungültige Operanden.")
            if isinstance(node.op, ast.Add): return left + right
            if isinstance(node.op, ast.Sub): return left - right
            if isinstance(node.op, ast.Mult): return left * right
            if isinstance(node.op, ast.Div):
                if right == 0: raise MathInputError("Division durch null.")
                return left / right
            if isinstance(node.op, ast.Pow):
                if right.is_number and (right.is_real is False or right.is_finite is False or abs(right) > 10000):
                    raise MathInputError("Numerischer Exponent überschreitet das Budget.")
                return left ** right
            if isinstance(node.op, ast.Mod): return s.Mod(left, right)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and not node.keywords:
            name = node.func.id
            if name not in FUNCTIONS:
                # f/g/y/u are mathematical undefined functions for ODEs, not Python calls.
                if name not in ("f", "g", "y", "u"):
                    raise MathInputError("Unbekannte mathematische Funktion: " + name)
                function = s.Function(name)
            else:
                function = FUNCTIONS[name]
            if len(node.args) > 16:
                raise MathInputError("Zu viele Funktionsargumente.")
            args = [child(arg) for arg in node.args]
            if name in ("factorial", "binomial") and any(isinstance(a, s.Integer) and abs(a) > 10000 for a in args):
                raise MathInputError("Argument überschreitet das Zahlenbudget.")
            if name == "Matrix" and (len(args) != 1 or not isinstance(args[0], list)):
                raise MathInputError("Matrix erwartet eine Liste von Zeilen.")
            value = function(*args)
            if isinstance(value, s.MatrixBase) and value.rows * value.cols > 100:
                raise MathInputError("Matrix: höchstens 100 Zellen.")
            return value
        raise MathInputError("Nicht erlaubte Syntax; nutze mathematische Namen, Funktionen und Operatoren.")


def _integer(options, key, default, maximum):
    text = options.get(key, str(default))
    if not re.fullmatch(r"[0-9]{1,3}", text) or not 1 <= int(text) <= maximum:
        raise MathInputError(key + " außerhalb des erlaubten Bereichs.")
    return int(text)


def calculate(options):
    if not isinstance(options, dict) or set(options) - OPTIONS or any(not isinstance(v, str) for v in options.values()):
        raise MathInputError("Unbekannte Argumente; alle Argumentwerte müssen Strings sein.")
    if sum(len(v) for v in options.values()) > 8192:
        raise MathInputError("Argumente zu groß.")
    op = options.get("op")
    if op not in OPERATIONS:
        raise MathInputError("Unbekannte SymPy-Operation.")
    domain = options.get("domain", "complex")
    if domain not in ("real", "complex"):
        raise MathInputError("domain muss real oder complex sein.")
    names = [v.strip() for v in options.get("vars", options.get("var", "x")).split(",")]
    if len(names) > 16 or len(set(names)) != len(names):
        raise MathInputError("Ungültige Variablenliste.")
    assumptions = json.loads(options.get("assumptions", "{}"))
    if not isinstance(assumptions, dict):
        raise MathInputError("Annahmen müssen ein JSON-Objekt sein.")
    if domain == "real":
        for name in names:
            flags = assumptions.setdefault(name, {})
            if not isinstance(flags, dict) or flags.get("real") is False:
                raise MathInputError("Widersprüchliche reelle Symbolannahmen.")
            flags["real"] = True
    parser = Parser(assumptions)
    expr = parser.parse(options.get("expr", ""))
    var = parser.symbol(options.get("var", "x"))
    variables = [parser.symbol(name) for name in names]
    precision = _integer(options, "precision", 30, 100)
    order = _integer(options, "order", 6 if op == "series" else 1, 20)
    notes = []
    if op in ("simplify", "expand", "factor", "cancel", "apart", "trigsimp"):
        notes.append("Definitionsbedingungen des ursprünglichen Ausdrucks gelten weiterhin, auch nach Kürzen.")
        result = getattr(s, op)(expr)
    elif op == "differentiate": result = s.diff(expr, var, order)
    elif op in ("integrate", "summation", "product"):
        bounded = "lower" in options or "upper" in options
        if bounded and not ("lower" in options and "upper" in options):
            raise MathInputError("lower und upper müssen gemeinsam angegeben werden.")
        if op != "integrate" and not bounded:
            raise MathInputError("Summen und Produkte benötigen lower und upper.")
        bounds = (var, parser.parse(options["lower"]), parser.parse(options["upper"])) if bounded else var
        result = getattr(s, op)(expr, bounds)
        if op == "integrate" and not bounded: notes.append("Unbestimmtes Integral: Integrationskonstante + C ergänzen.")
    elif op in ("solve", "solveset"):
        if isinstance(expr, (list, tuple)):
            if op == "solveset": raise MathInputError("solveset erwartet eine einzelne Gleichung.")
            result = s.solve(expr, variables, dict=True)
            notes.append("Systemlösung durch solve; Parameter- und Sonderfälle beachten.")
        else:
            result = s.solveset(expr, var, domain=s.S.Reals if domain == "real" else s.S.Complexes)
    elif op in ("linsolve", "nonlinsolve"):
        if not isinstance(expr, (list, tuple)): expr = [expr]
        result = getattr(s, op)(expr, variables)
    elif op == "nsolve":
        if "point" not in options: raise MathInputError("nsolve benötigt point als Startwert oder Startwertliste.")
        result = s.nsolve(expr, variables if isinstance(expr, (list, tuple)) else var, parser.parse(options["point"]), prec=precision, verify=True)
        notes.append("Numerische lokale Lösung; abhängig vom Startwert, keine vollständige Lösungsmenge.")
    elif op == "dsolve": result = s.dsolve(expr)
    elif op == "limit":
        if "point" not in options: raise MathInputError("limit benötigt point.")
        direction = options.get("direction", "+")
        if direction not in ("+", "-", "+-"): raise MathInputError("Ungültige Grenzwertrichtung.")
        result = s.limit(expr, var, parser.parse(options["point"]), dir=direction)
    elif op == "series": result = s.series(expr, var, parser.parse(options.get("point", "0")), order)
    elif op == "numeric": result = s.N(expr, precision)
    elif op in ("det", "inverse", "rank", "eigenvalues", "transpose", "rref"):
        if not isinstance(expr, s.MatrixBase):
            if not isinstance(expr, list): raise MathInputError("Matrixoperation erwartet Matrix([[...], ...]).")
            expr = s.Matrix(expr)
        if expr.rows * expr.cols > 100: raise MathInputError("Matrix: höchstens 100 Zellen.")
        method = {"inverse": "inv", "eigenvalues": "eigenvals", "transpose": "transpose"}.get(op, op)
        result = getattr(expr, method)()
    elif op in ("gcd", "lcm"):
        if not isinstance(expr, (list, tuple)) or len(expr) != 2: raise MathInputError(op + " erwartet [Ausdruck1, Ausdruck2].")
        result = getattr(s, op)(*expr)
    elif op in ("factorint", "isprime"):
        if not isinstance(expr, s.Integer) or abs(int(expr)).bit_length() > 128: raise MathInputError("Zahlentheorie: ganze Zahl mit höchstens 128 Bit.")
        result = getattr(s, op)(expr)
    else: raise MathInputError("Nicht unterstützte Operation.")
    if isinstance(result, s.Basic) and result.has(s.zoo, s.nan):
        raise MathInputError("Ergebnis ist nicht definiert.")
    text = s.sstr(result)
    if len(text) > MAX_OUTPUT: raise MathInputError("Ergebnis überschreitet das Ausgabebudget.")
    latex = s.latex(result)
    if len(latex) > MAX_OUTPUT: latex = ""
    symbolic = (s.Integral, s.Derivative, s.Limit, s.Sum, s.Product, s.ConditionSet)
    def contains_pending(value):
        if isinstance(value, s.MatrixBase): return any(contains_pending(v) for v in value)
        if isinstance(value, dict): return any(contains_pending(v) for v in value.values())
        if isinstance(value, (list, tuple)): return any(contains_pending(v) for v in value)
        return isinstance(value, s.Basic) and value.has(*symbolic)
    unevaluated = bool(contains_pending(result))
    if unevaluated: notes.append("SymPy hat Teile symbolisch/bedingt stehen gelassen; nicht als vollständig ausgewertet darstellen.")
    if isinstance(result, s.Basic) and result.has(s.oo, -s.oo): notes.append("Nicht endlicher Wert; Konvergenz und Richtung beachten.")
    return {"engine": "sympy", "version": s.__version__, "op": op, "result": text,
            "latex": latex, "exact": op not in ("numeric", "nsolve"), "unevaluated": unevaluated,
            "domain": domain, "assumptions": json.dumps(assumptions, sort_keys=True), "notes": notes}


def run_json(payload):
    try:
        result = {"ok": True, "result": calculate(json.loads(payload))}
    except Exception as exc:
        result = {"ok": False, "error": str(exc)[:500] or type(exc).__name__}
    return json.dumps(result, ensure_ascii=False, allow_nan=False)


def constrain_worker():
    """Best-effort OS limits in the disposable Android process; Java enforces wall time."""
    try:
        import os
        import resource
        with open("/proc/self/statm", encoding="ascii") as status:
            virtual_bytes = int(status.read().split()[0]) * os.sysconf("SC_PAGE_SIZE")
        resource.setrlimit(resource.RLIMIT_AS, (virtual_bytes + 256 * 1024 * 1024,) * 2)
        elapsed = os.times().user + os.times().system
        resource.setrlimit(resource.RLIMIT_CPU, (int(elapsed) + 15, int(elapsed) + 16))
    except (ImportError, OSError, ValueError, AttributeError):
        pass
