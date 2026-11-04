"""Modulo auxiliar do exemplo (prova que o pacote leva mais de um arquivo e importa da propria pasta)."""


def total_por_cliente(linhas):
    out = {}
    for row in linhas:
        out[row["cliente"]] = round(out.get(row["cliente"], 0.0) + float(row["valor"]), 2)
    return out
