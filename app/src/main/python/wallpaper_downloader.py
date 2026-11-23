import contextlib
import io
import json
import sys


def resolve(archive, arguments):
    if archive not in sys.path:
        sys.path.insert(0, archive)
    import yt_dlp

    output, errors = io.StringIO(), io.StringIO()
    status = 0
    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
        try:
            yt_dlp.main(json.loads(arguments))
        except SystemExit as error:
            status = error.code or 0
    return json.dumps([status, output.getvalue(), errors.getvalue()])
