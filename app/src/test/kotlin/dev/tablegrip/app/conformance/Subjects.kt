package dev.tablegrip.app.conformance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

const val SubjectTag = "subject"
const val NextTag = "next"

/** What a subject exposes to the runner after the steps: values keyed like a scenario's `expect`. */
class Probe {
    val readers = mutableMapOf<String, () -> String>()
}

/**
 * Hosts one component the way the app uses it, tagged [SubjectTag], and registers in the probe what scenarios may
 * expect of it. Each lives in its own file in the `subjects` package as an `object`; [Subjects] finds them, so adding
 * a component never touches a shared file.
 */
interface ConformanceSubject {
    /** The `component` name scenario files use. */
    val component: String

    @Composable fun Content(setup: JsonObject, probe: Probe)
}

object Subjects {
    private const val Package = "dev.tablegrip.app.conformance.subjects"

    val all: Map<String, ConformanceSubject> by lazy {
        val dirs = javaClass.classLoader.getResources(Package.replace('.', '/')).toList().filter { it.protocol == "file" }.map { File(it.toURI()) }
        dirs
            .flatMap { dir -> dir.listFiles { f -> f.extension == "class" && '$' !in f.name }.orEmpty().toList() }
            .map { Class.forName("$Package.${it.nameWithoutExtension}") }
            .filter { ConformanceSubject::class.java.isAssignableFrom(it) }
            .map { it.getField("INSTANCE").get(null) as ConformanceSubject }
            .associateBy { it.component }
    }
}

/** The subject for [component], with a second focusable control after it when `setup.next` is set. */
@Composable
fun Subject(component: String, setup: JsonObject, probe: Probe) {
    val subject = Subjects.all[component] ?: error("No Kotlin subject for component '$component'")
    Column(Modifier.padding(16.dp)) {
        subject.Content(setup, probe)
        if (setup.bool("next")) OutlinedButton(onClick = {}, modifier = Modifier.testTag(NextTag)) { Text("Next") }
    }
}
