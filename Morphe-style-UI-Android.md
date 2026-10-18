# Interface « style Morphe » pour une appli Android (Jetpack Compose)

Guide pour reproduire, dans n'importe quel projet Android en Compose, l'interface mise au point dans **UmaPatcher DX** (inspirée de Morphe Manager). Il est écrit pour être donné tel quel à **Claude Code**.

---

## 1. Comment l'utiliser avec Claude Code

1. Copie ce fichier dans ton projet, par exemple `docs/ui-style.md`.
2. Ajoute une ligne dans le `CLAUDE.md` du projet pour que Claude Code le suive toujours :
   ```markdown
   - UI: suivre docs/ui-style.md (style Morphe : cartes arrondies à bordure, barre flottante / rail, listes à coche, écrans deux panneaux en large).
   ```
3. Demande ensuite par étapes, par exemple :
   - « Applique docs/ui-style.md : crée `ui/component/Cards.kt` et la barre de navigation flottante. »
   - « Refais l'écran Réglages selon docs/ui-style.md. »
   - « Ajoute le mode écran large (rail + deux colonnes) selon docs/ui-style.md. »
4. Envoie des captures de Morphe ou de l'appli de référence : Claude Code s'aligne beaucoup mieux sur une image que sur une description.
5. Fais tester sur un vrai appareil (débogage sans fil + `adb exec-out screencap`). Sur un Fold, chaque écran a son propre identifiant d'affichage : `adb shell dumpsys SurfaceFlinger --display-id`, puis `screencap -d <id>`.

> **Licence :** Morphe Manager est sous **GPLv3**, et son nom et son logo sont protégés. Le code ci-dessous a été **réécrit** (il n'est pas copié de Morphe), donc il est réutilisable même dans un projet non-GPL. Ne copie pas de fichiers de Morphe Manager dans un projet non-GPL, et n'utilise ni le nom ni le logo « Morphe ».

---

## 2. Prérequis

- Jetpack Compose + **Material 3** (fonctionne dès `material3:1.1.2` ; plus récent conseillé).
- Icônes : le jeu de base `androidx.compose.material.icons` suffit (`Check`, `Close`, `KeyboardArrowRight`, `Settings`, `Build`, `Star`, `Delete`…). `material-icons-extended` donne plus de choix mais alourdit l'APK sans minification R8.
- Aucune autre dépendance.

---

## 3. Règles de design (tokens)

| Élément | Valeur |
|---|---|
| Fond | `colorScheme.background` + 2 halos radiaux (primary 14 %, tertiary 10 %) |
| Carte de section | rayon **18dp**, bordure **1dp** `outlineVariant`, fond `surfaceColorAtElevation(3.dp)` |
| Élément intérieur (tuile, sous-carte) | rayon **14dp** |
| Marges | 16dp sur les côtés ; 8dp entre cartes de réglages ; 16dp entre blocs |
| Titre d'écran | `headlineLarge`, SemiBold, sans TopAppBar |
| Titre de section | icône 20dp `primary` + `titleSmall` SemiBold `onSurfaceVariant` |
| Ligne de réglage | icône 24dp `primary` · titre `titleMedium` · description `bodyMedium onSurfaceVariant` · chevron ou switch |
| Switch | coche ✓ / croix ✕ dans le pouce |
| Sélection | cercle plein `primary` avec ✓ (sélectionné) ou cercle bordé 2dp `outline` |
| Bouton d'action | pilule, hauteur **56dp**, plein (`Button`) ou bordé (`OutlinedButton`) |
| Badge (« pill ») | `CircleShape`, padding 12×6dp, `labelLarge` |
| Barre du bas | pilule flottante centrée, 16dp du bas, élévation 8dp, onglet actif = pilule `primaryContainer` + bordure `primary` 1.5dp |
| Écran large | dès **600dp** de large : rail à gauche (200dp), colonnes centrées max **840dp**, deux panneaux pour progression/résultat |
| Marge sous le contenu | **112dp** en bas (pour que la barre flottante ne masque rien) |

---

## 4. Composants : `ui/component/Cards.kt`

Fichier unique à copier tel quel (adapter le `package`).

```kotlin
package com.example.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Room left under scrolling content so the floating bottom bar never hides its last item. */
val BottomBarClearance = 112.dp

/** Widest a single column of content grows to on large screens, so lines stay readable. */
val ContentMaxWidth = 840.dp

/** True on screens wide enough for two panes, such as an unfolded Fold or a tablet. */
@Composable
fun isWideScreen() = LocalConfiguration.current.screenWidthDp >= 600

/** Fills the screen but keeps the content a centered column no wider than [ContentMaxWidth]. */
fun Modifier.centeredContent() = fillMaxSize()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = ContentMaxWidth)

val SectionShape = RoundedCornerShape(18.dp)

@Composable
fun cardFill(): Color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)

/** Rounded, outlined card every section of the app is built from. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        shape = SectionShape,
        color = cardFill(),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(content = content)
    }
}

/** Large screen title, with optional subtitle, back icon and round action buttons on the right. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (navigationIcon != null) {
            navigationIcon()
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** Card washed with a diagonal gradient of the theme's containers, for the item it stands for. */
@Composable
fun GradientCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .background(Brush.linearGradient(listOf(colors.tertiaryContainer, colors.primaryContainer, colors.secondaryContainer)))
            .border(1.dp, colors.outline.copy(alpha = 0.5f), SectionShape)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Round tonal button used in [ScreenHeader] (search, add, sort…). */
@Composable
fun HeaderIconButton(icon: @Composable () -> Unit, onClick: () -> Unit, highlighted: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else cardFill(),
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(52.dp)
    ) {
        IconButton(onClick = onClick, content = icon)
    }
}

@Composable
fun SectionTitle(text: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icon, title, description and trailing content laid out as one settings row. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A settings row in its own card, leading to another screen or action. */
@Composable
fun SettingsItem(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leadingIcon: Painter? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = {
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
) {
    SectionCard(onClick = onClick, enabled = enabled, modifier = if (enabled) Modifier else Modifier.alpha(0.38f)) {
        SettingsRow(
            title = title,
            subtitle = subtitle,
            icon = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } }
                ?: leadingIcon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
            trailing = trailing
        )
    }
}

/** A settings row in its own card with a switch; tapping anywhere toggles it. */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    SettingsItem(
        title = title, subtitle = subtitle, icon = icon, enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { ToggleSwitch(checked = checked, enabled = enabled) }
    )
}

/** Switch showing a check or a cross in its thumb. Not clickable itself: its row is. */
@Composable
fun ToggleSwitch(checked: Boolean, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
        thumbContent = {
            Icon(if (checked) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(SwitchDefaults.IconSize))
        }
    )
}

/** Small rounded label, such as a status or a version. */
@Composable
fun Pill(
    text: String,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(shape = CircleShape, color = containerColor, contentColor = contentColor) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One labelled value of an info card: small label above, value below. */
@Composable
fun InfoRow(label: String, value: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        trailing?.invoke()
    }
}

/** Card stacking [rows] with dividers between them. */
@Composable
fun DividedCard(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    SectionCard(modifier = modifier, borderColor = borderColor) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Divider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

/** Selectable row of a [DividedCard]: a check circle, a title, an optional subtitle and badge. */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            leading != null -> Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
            selected -> Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Check, null, Modifier.padding(6.dp), tint = colors.onPrimary)
            }
            else -> Box(Modifier.size(32.dp).border(2.dp, colors.outline, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        badge?.invoke()
    }
}

/** Large pill-shaped action button; [primary] fills it, otherwise it is outlined. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val buttonModifier = modifier.height(56.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = buttonModifier, content = content)
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = buttonModifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            content = content
        )
    }
}
```

---

## 5. Squelette racine : fond, barre flottante, rail

À mettre dans `MainActivity` (ou le composable racine). Cette version ne dépend d'aucune librairie de navigation : on lui passe une liste d'onglets et on branche `onClick` sur sa propre navigation (Navigation Compose, compose-destinations…).

```kotlin
data class Tab(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

@Composable
fun AppRoot(tabs: List<Tab>, showTabs: Boolean, content: @Composable () -> Unit) {
    // IMPORTANT: the root Surface provides the content color; without it Text renders black on dark themes
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().glowBackground()) {
            val wide = isWideScreen()
            Row(Modifier.fillMaxSize()) {
                if (wide && showTabs) SideRail(tabs)
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
            if (!wide && showTabs) FloatingTabBar(tabs, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Two soft color halos behind the content, drawn as radial gradients that fade to nothing. */
@Composable
fun Modifier.glowBackground(): Modifier {
    val first = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val second = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    return drawBehind {
        val radius = size.maxDimension * 0.6f
        val topRight = Offset(size.width, size.height * 0.15f)
        val bottomLeft = Offset(0f, size.height * 0.8f)
        drawCircle(Brush.radialGradient(listOf(first, Color.Transparent), topRight, radius), radius, topRight)
        drawCircle(Brush.radialGradient(listOf(second, Color.Transparent), bottomLeft, radius), radius, bottomLeft)
    }
}

@Composable
fun FloatingTabBar(tabs: List<Tab>, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp), // keep it opaque
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = modifier.padding(bottom = 16.dp)
    ) {
        Row(Modifier.padding(6.dp)) {
            tabs.forEach { tab ->
                Surface(
                    onClick = tab.onClick,
                    shape = CircleShape,
                    color = if (tab.selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (tab.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (tab.selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.width(104.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp)) {
                        Icon(tab.icon, tab.label)
                        Text(tab.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Large-screen counterpart of [FloatingTabBar]: tab pills stacked left of the content. */
@Composable
fun SideRail(tabs: List<Tab>) {
    Row {
        Column(
            modifier = Modifier.width(200.dp).fillMaxHeight().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            tabs.forEach { tab ->
                Surface(
                    onClick = tab.onClick,
                    shape = CircleShape,
                    color = if (tab.selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (tab.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(
                        if (tab.selected) 1.5.dp else 1.dp,
                        if (tab.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(tab.icon, null)
                        Spacer(Modifier.width(12.dp))
                        Text(tab.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Box(Modifier.padding(vertical = 48.dp).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    }
}
```

`showTabs` doit être vrai **uniquement sur les écrans d'onglet**. Les sous-écrans (détail, progression, sélection) prennent toute la hauteur et ont une flèche retour dans leur `ScreenHeader`.

---

## 6. Recettes d'écrans

### Écran d'onglet (ex. Réglages)
```kotlin
Column(
    Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    ScreenHeader("Settings")
    SettingsSwitchItem("Check for updates", checked, { checked = it }, subtitle = "…", icon = Icons.Filled.Refresh)
    SectionTitle("Patching", Icons.Filled.Build)
    SettingsItem("Advanced", onClick = { /* navigate */ }, subtitle = "…", icon = Icons.Filled.Settings)
    Spacer(Modifier.height(BottomBarClearance)) // only where the floating bar shows
}
```

### Choix exclusif (ex. méthode d'installation, source de fichiers)
```kotlin
SectionTitle("Install method", Icons.Filled.Settings)
DividedCard(methods.mapIndexed { i, label ->
    { ChoiceRow(label, selected = method == i, onClick = { method = i },
        badge = if (i == 3) { { Pill("Available", containerColor = Color(0xFF1B5E20), contentColor = Color(0xFFC8E6C9)) } } else null) }
})
ActionButton("Patch", onClick = ::start, icon = Icons.Filled.Build, primary = true, modifier = Modifier.fillMaxWidth())
```
Une ligne qui mène ailleurs (pas un choix) : `ChoiceRow(..., selected = false, leading = { Icon(...) }, badge = { Icon(Icons.Filled.KeyboardArrowRight, null) })`.

### Carte « élément » mise en avant
`GradientCard` + icône 64dp en `RoundedCornerShape(16.dp)` + nom en `titleLarge` SemiBold + version + `Pill` d'état.

### Écran de progression (long traitement)
- **En-tête :** flèche retour (désactivée pendant le travail) + nom de l'élément + ligne d'état colorée (primary pendant le travail, vert `0xFF81C784` si succès, `error` si échec) + `Pill` du pourcentage.
- **Barre :** `LinearProgressIndicator` de 8dp avec `clip(CircleShape)`, indéterminée tant que la progression est inconnue.
- **Carte d'infos :** titre coloré, séparateur, puis valeurs **deux par ligne** (libellé en `labelSmall` MAJUSCULES au-dessus de la valeur).
- **Journal :** dans une `SectionCard` qui défile ; une ligne = petit repère vertical (4×12dp) + texte, les étapes mises en avant en gras avec un repère `primary`. Une carte de résultat (durée, statut) en bas.
- **Boutons du bas :** Fermer/Terminé + Copier (presse-papiers).
- **Grand écran :** panneau gauche (≈42 % : en-tête, barre, infos, boutons en bas) et panneau droit (≈58 % : journal seul).

### Écran de résultat (succès)
- Icône 128dp avec pastille verte ✓ en bas à droite (`offset(12.dp, 12.dp)`, bordure 4dp couleur du fond), `Pill` « Installé », titre `headlineMedium`, phrase de confirmation.
- `DividedCard` d'`InfoRow` (version, méthode, durée…).
- Boutons : **Ouvrir** (plein) puis **Journaux** / **Accueil** (bordés).
- **Grand écran :** identité à gauche, carte de détails à droite, tous les boutons sur une ligne en bas.

### Grand écran (≥ 600dp)
- Accueil : `Row` de deux colonnes qui défilent chacune (`Modifier.weight(1f)`).
- Listes et réglages : `Modifier.centeredContent()` (colonne centrée, 840dp max).
- Pas de `BottomBarClearance` dans les colonnes quand le rail remplace la barre.

---

## 7. Pièges déjà rencontrés (à dire à Claude Code)

1. **Texte noir sur fond sombre :** sans `Surface` racine, aucune couleur de contenu n'est fournie. Toujours envelopper la racine dans `Surface(color = background)`.
2. **Barre flottante semi-transparente :** le contenu se lit à travers. La garder opaque (`surfaceColorAtElevation(6.dp)`).
3. **Contenu caché sous la barre :** finir chaque écran d'onglet par `Spacer(Modifier.height(BottomBarClearance))`.
4. **Défilement auto du journal :** pour descendre en bas après un changement d'écran, attendre une frame : `LaunchedEffect(log.size, completed) { withFrameNanos {}; scrollState.scrollTo(scrollState.maxValue) }`.
5. **Police à chasse fixe ignorée** sur certains Samsung : ne pas compter dessus pour aligner du texte.
6. **Préférences :** enregistrer immédiatement (DataStore) plutôt qu'avec un délai, sinon un arrêt rapide de l'appli perd le choix.
7. **Fenêtres « une seule fois » :** une fenêtre affichée pendant que l'écran est verrouillé peut se fermer toute seule. Ne marquer « vu » que sur un clic de bouton si c'est important.
8. **Échec signalé comme succès :** exposer un vrai état `succeeded` et l'afficher (icône d'alerte rouge), pas seulement `completed`.
9. **`Surface(onClick = …)`** est expérimental sur les anciennes versions de Material 3 : si la compilation le refuse, ajouter `@OptIn(ExperimentalMaterial3Api::class)`.
10. **Fold :** l'écran externe et l'écran interne ont des identifiants d'affichage différents pour `screencap`. Tester les deux tailles.

---

## 8. Checklist de demande pour un nouveau projet

> « Applique docs/ui-style.md à ce projet :
> 1. crée `ui/component/Cards.kt` (section 4) ;
> 2. remplace la barre de navigation par `AppRoot` / barre flottante / rail (section 5), visible seulement sur les onglets ;
> 3. refais les écrans d'onglet avec `ScreenHeader` + cartes + `BottomBarClearance` ;
> 4. transforme les choix exclusifs en `DividedCard` + `ChoiceRow` ;
> 5. ajoute le mode ≥ 600dp (rail, colonnes centrées, deux panneaux) ;
> 6. compile, installe sur l'appareil et montre-moi des captures des deux tailles d'écran. »
