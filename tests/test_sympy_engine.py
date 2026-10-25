"""Real CAS tests run the exact Python module packaged in the Android APK."""
import importlib.util
import json
from pathlib import Path
import unittest
import sympy as s

MODULE = Path(__file__).resolve().parents[1] / "app/src/main/python/bonsai_sympy.py"
spec = importlib.util.spec_from_file_location("bonsai_sympy", MODULE)
cas = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cas)


class SympyEngineTests(unittest.TestCase):
    def run_math(self, op, expr, **options):
        reply = json.loads(cas.run_json(json.dumps(dict(op=op, expr=expr, **options))))
        self.assertTrue(reply["ok"], reply)
        self.assertEqual("sympy", reply["result"]["engine"])
        self.assertEqual("1.14.0", reply["result"]["version"])
        return reply["result"]

    def equal_expression(self, op, expr, expected, **options):
        result = self.run_math(op, expr, **options)
        self.assertEqual(0, s.simplify(s.sympify(result["result"]) - s.sympify(expected)))
        self.assertTrue(result["exact"])
        return result

    def test_twenty_transcendental_calculus_cases(self):
        examples = [
            ("sin(x)", "-cos(x)"), ("cos(x)", "sin(x)"), ("exp(x)", "exp(x)"),
            ("1/x", "log(x)"), ("log(x)", "x*log(x)-x"),
            ("sqrt(x)", "2*x**(s.Rational(3,2))/3".replace("s.Rational", "Rational")),
            ("1/(1+x^2)", "atan(x)"), ("sinh(x)", "cosh(x)"),
            ("cosh(x)", "sinh(x)"), ("x*exp(x)", "(x-1)*exp(x)"),
        ]
        for expr, integral in examples:
            with self.subTest(expr=expr):
                result = self.equal_expression("integrate", expr, integral)
                self.assertIn("+ C", result["notes"][0])
                self.equal_expression("differentiate", integral.replace("**", "^"), expr)

    def test_twenty_algebra_and_exact_arithmetic_cases(self):
        for n in range(2, 12):
            with self.subTest(n=n):
                self.equal_expression("factor", "x^2-" + str(n*n), "(x-"+str(n)+")*(x+"+str(n)+")")
                self.equal_expression("simplify", "1/"+str(n)+"+1/"+str(n), "2/"+str(n))
        self.equal_expression("cancel", "(x^2-1)/(x-1)", "x+1")
        self.equal_expression("trigsimp", "sin(x)^2+cos(x)^2", "1")
        self.equal_expression("apart", "1/(x^2-1)", "1/(2*(x-1))-1/(2*(x+1))")
        self.equal_expression("expand", "(x+y)^3", "x^3+3*x^2*y+3*x*y^2+y^3")

    def test_equations_systems_complex_and_ode(self):
        for n in range(1, 11):
            with self.subTest(n=n):
                self.assertEqual("{"+str(n)+"}", self.run_math("solve", "x="+str(n))["result"])
        self.assertEqual("{-I, I}", self.run_math("solve", "x^2+1=0")["result"])
        self.assertEqual("EmptySet", self.run_math("solve", "x^2+1=0", domain="real")["result"])
        self.assertEqual("{1}", self.run_math("solve", "x^3=1", domain="real")["result"])
        self.assertEqual("[{x: 2, y: 1}]", self.run_math("solve", '["x+y=3","x-y=1"]', vars="x,y")["result"])
        self.assertEqual("[]", self.run_math("solve", '["x^2+1=0"]', vars="x", domain="real")["result"])
        self.assertEqual("{(2, 1)}", self.run_math("linsolve", "[Eq(x+y,3),Eq(x-y,1)]", vars="x,y")["result"])
        ode = self.run_math("dsolve", "Eq(Derivative(y(x),x),y(x))")["result"]
        self.assertEqual("Eq(y(x), C1*exp(x))", ode)
        self.assertTrue("I" in self.run_math("solve", "x^4=1")["result"])

    def test_limits_series_sums_and_definite_integrals(self):
        for expr, point, expected in [("sin(x)/x","0","1"), ("(1+1/x)^x","oo","E"),
                                      ("(exp(x)-1)/x","0","1"), ("1/x","oo","0")]:
            self.equal_expression("limit", expr, expected, point=point)
        self.equal_expression("integrate", "sin(x)", "2", lower="0", upper="pi")
        self.equal_expression("integrate", "exp(-x)", "1", lower="0", upper="oo")
        self.equal_expression("summation", "1/n^2", "pi^2/6", var="n", lower="1", upper="oo")
        self.equal_expression("summation", "n", "55", var="n", lower="1", upper="10")
        self.equal_expression("product", "n", "120", var="n", lower="1", upper="5")
        self.assertEqual("x - x**3/6 + x**5/120 + O(x**7)", self.run_math("series", "sin(x)", order="7")["result"])
        self.equal_expression("differentiate", "x^5", "60*x^2", order="3")

    def test_matrices_number_theory_and_numeric_precision(self):
        matrix = "Matrix([[1,2],[3,4]])"
        self.assertEqual("-2", self.run_math("det", matrix)["result"])
        self.assertEqual("2", self.run_math("rank", matrix)["result"])
        self.assertEqual(s.Matrix([[-2, 1], [s.Rational(3, 2), s.Rational(-1, 2)]]), s.sympify(self.run_math("inverse", matrix)["result"]))
        self.assertEqual(s.Matrix([[1, 3], [2, 4]]), s.sympify(self.run_math("transpose", matrix)["result"]))
        self.assertEqual("{2: 1, 3: 1}", self.run_math("eigenvalues", "[[2,0],[0,3]]")["result"])
        self.assertIn("(0, 1)", self.run_math("rref", matrix)["result"])
        self.equal_expression("gcd", "[x^2-1,x^2+2*x+1]", "x+1")
        self.equal_expression("lcm", "[12,18]", "36")
        self.assertEqual("{2: 3, 3: 2, 5: 1}", self.run_math("factorint", "360")["result"])
        self.assertEqual("True", self.run_math("isprime", "104729")["result"])
        numerical = self.run_math("numeric", "pi", precision="50")
        self.assertFalse(numerical["exact"])
        self.assertTrue(numerical["result"].startswith("3.1415926535897932384626433832795028841971693993751"))
        self.assertTrue(self.run_math("nsolve", "cos(x)-x", point="1")["result"].startswith("0.7390851332"))

    def test_assumptions_and_unevaluated_results_are_explicit(self):
        self.equal_expression("simplify", "sqrt(x^2)", "x", assumptions='{"x":{"positive":true}}')
        self.assertEqual("sqrt(x**2)", self.run_math("simplify", "sqrt(x^2)")["result"])
        result = self.run_math("integrate", "x^x")
        self.assertTrue(result["unevaluated"])
        self.assertTrue(result["notes"])
        self.assertIn('"real": true', self.run_math("solve", "x=1", domain="real")["assumptions"])

    def test_untrusted_syntax_and_limits_are_rejected(self):
        bad = ["__import__('os')", "open('/tmp/test')", "x.__class__", "sin.__globals__",
               "(lambda:1)()", "[x for x in y]", "x[0]", "{'x':1}", "sin(x,foo=1)",
               "exec(1)", "eval(1)", "Symbol('x')", "1/0", "10^10001", "factorial(10001)",
               "x"*4097, "sin("*45+"x"+")"*45, "x^"+"9"*1300]
        for expr in bad:
            with self.subTest(expr=expr[:60]):
                self.assertFalse(json.loads(cas.run_json(json.dumps(dict(op="simplify",expr=expr))))["ok"])
        for args in [dict(op="exec",expr="1"),dict(op="simplify",expr="x",extra="1"),
                     dict(op="integrate",expr="x",lower="0"),dict(op="limit",expr="x"),
                     dict(op="series",expr="sin(x)",order="999"),dict(op="numeric",expr="pi",precision="1000"),
                     dict(op="simplify",expr="x",assumptions='{"x":{"positive":"yes"}}'),
                     dict(op="simplify",expr="x",assumptions='{"x":{"real":false}}',domain="real")]:
            with self.subTest(args=args): self.assertFalse(json.loads(cas.run_json(json.dumps(args)))["ok"])


if __name__ == "__main__":
    unittest.main()
