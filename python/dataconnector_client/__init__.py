"""Python client for the Multi-Language Data Connector API."""

import os
import sys

# Generated code lives in python/generated and imports itself as "dataconnector.v1...".
_GENERATED = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "generated")
if _GENERATED not in sys.path:
    sys.path.insert(0, _GENERATED)
