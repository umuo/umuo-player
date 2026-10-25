package app.umuo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage

object Cinema {
    val Background=Color(0xFF090B10)
    val Surface=Color(0xFF14171F)
    val Elevated=Color(0xFF20242E)
    val Accent=Color(0xFFF0BB77)
    val Text=Color(0xFFF5F3EF)
    val Muted=Color(0xFF939AA9)
    val Border=Color(0xFF292D36)
}
@Composable fun CinemaTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=Cinema.Accent,onPrimary=Cinema.Background,
        background=Cinema.Background,surface=Cinema.Surface,onSurface=Cinema.Text,
        secondary=Cinema.Accent,secondaryContainer=Cinema.Elevated,onSecondaryContainer=Cinema.Accent,
        surfaceVariant=Cinema.Elevated,onSurfaceVariant=Cinema.Muted,outline=Cinema.Border),
        shapes=Shapes(small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(20.dp)),content=content)
}
@Composable fun CinemaWordmark(modifier:Modifier=Modifier) {
    Text("umuo",modifier,color=Cinema.Text,fontSize=27.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
}
@Composable fun CinemaArtwork(uri:String?,title:String,loader:ImageLoader,modifier:Modifier=Modifier,hero:Boolean=false,version:Long=0) {
    Box(modifier.background(Cinema.Surface),contentAlignment=Alignment.Center) {
        if(uri!=null) AsyncImage(coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(uri).memoryCacheKey("$uri:$version").build(),title,imageLoader=loader,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Icon(Icons.Rounded.Movie,title,tint=Cinema.Muted.copy(alpha=.35f),modifier=Modifier.size(if(hero)72.dp else 36.dp))
        if(hero) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.08f),Cinema.Background.copy(alpha=.15f),Cinema.Background))))
    }
}

fun timeLabel(ms:Long):String = "${ms / 60_000}:${((ms / 1000) % 60).toString().padStart(2,'0')}"
