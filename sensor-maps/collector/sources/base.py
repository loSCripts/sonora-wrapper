"""Ce que chaque source renvoie au programme principal."""
from dataclasses import dataclass, field


@dataclass
class Resultat:
    fichiers: dict                                  # nom du fichier -> contenu JSON à écrire
    nb: int                                         # nombre de mesures ou d'événements
    periode: str = ''                               # texte court affiché sur la carte (« 7 derniers jours »)
    garder: list = field(default_factory=list)      # fichiers déjà publiés, repris sans changement

    def noms(self):
        return sorted(set(self.fichiers) | set(self.garder))


def dans_intervalle(v, mini, maxi):
    return v is not None and mini <= v <= maxi
