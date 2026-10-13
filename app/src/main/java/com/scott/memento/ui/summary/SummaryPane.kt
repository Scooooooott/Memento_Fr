package com.scott.memento.ui.summary

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.scott.memento.R
import com.scott.memento.domain.StudyGuard
import com.scott.memento.ui.AppState
import com.scott.memento.ui.MementoController
import com.scott.memento.ui.SummaryData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** History reads never activate the selected plan or create another daily allowance. */
@Composable
fun SummaryPane(state:AppState,controller:MementoController,planId:Long?,onDetail:(String)->Unit,
                onHome:()->Unit,onResume:()->Unit,modifier:Modifier=Modifier) {
    val selection=state.preferences?.selection ?: return
    var data by remember(selection.target,planId){mutableStateOf<SummaryData?>(null)}
    var loading by remember(selection.target,planId){mutableStateOf(true)}
    var error by remember(selection.target,planId){mutableStateOf<String?>(null)}
    LaunchedEffect(selection,planId,state.generation) {
        withContext(Dispatchers.Main.immediate) {loading=true;error=null}
        try {
            val loaded=planId?.let{controller.summary(selection,it)}
            coroutineContext.ensureActive()
            withContext(Dispatchers.Main.immediate) {data=loaded}
        }
        catch(e:CancellationException){throw e}
        catch(e:Exception){withContext(Dispatchers.Main.immediate) {error=e.message ?: e.javaClass.simpleName}}
        finally {withContext(Dispatchers.Main.immediate) {loading=false}}
    }
    val result=data
    when {
        result!=null->SummaryScreen(result.summary,result.focusLabels,state.busy,
            onDetail=onDetail,onHome=onHome,onResume=onResume,modifier=modifier,
            canResume=state.study?.let{it.planId==result.summary.planId && it.guard==StudyGuard.READY && it.next!=null}==true)
        loading->Text(stringResource(R.string.loading),modifier=modifier.testTag("summary_loading"))
        else->Text(error?.let{stringResource(R.string.operation_failed,it)} ?: stringResource(R.string.ui2_summary_unavailable),
            modifier=modifier.testTag("summary_unavailable"))
    }
}
