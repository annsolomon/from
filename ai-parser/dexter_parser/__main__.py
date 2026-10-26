"""python -m dexter_parser: the parser on 127.0.0.1:${PORT_AI_PARSER:-8090}."""
import os

import uvicorn

if __name__ == "__main__":
    uvicorn.run("dexter_parser.app:app", host=os.environ.get("PARSER_HOST", "127.0.0.1"),
                port=int(os.environ.get("PORT_AI_PARSER", "8090")), log_level="info", access_log=False)
