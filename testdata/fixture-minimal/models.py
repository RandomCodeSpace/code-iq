from django.db import models
from flask import Flask, Blueprint

app = Flask(__name__)
api = Blueprint("api", __name__)


class Author(models.Model):
    name = models.CharField(max_length=128)

    class Meta:
        db_table = "authors"


class Book(models.Model):
    title = models.CharField(max_length=200)
    author = models.ForeignKey(Author, on_delete=models.CASCADE)

    class Meta:
        db_table = "books"


@app.route("/health", methods=["GET"])
def health():
    return {"ok": True}


@api.route("/books", methods=["GET", "POST"])
def books_endpoint():
    return {"count": 0}
