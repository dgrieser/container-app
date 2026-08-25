"""Puts `ios/tools` on the import path for every test module here.
The generator's modules import each other by bare name (`import variants`)
because it is run as a script, not installed as a package. The tests mirror that
rather than turning the tool into a package for their own convenience, so this
runs once, before any test module is imported.
    cd ios/tools && python3 -m unittest discover -s tests -t .
"""
import pathlib
import sys
TOOLS = pathlib.Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
