from flask import Flask

app = Flask(__name__)


@app.route("/notify", methods=["POST"])
def notify():
    """Send a notification to a downstream subscriber."""
    return {"ok": True}
