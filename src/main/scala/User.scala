package lila.ws

object User:
  opaque type Id = String
  object Id extends OpaqueString[Id]
  opaque type Name = String
  object Name extends OpaqueString[Name]
  opaque type Title = String
  object Title extends OpaqueString[Title]
  opaque type TitleName = String
  object TitleName extends OpaqueString[TitleName]:
    def apply(name: Name, title: Option[Title]): TitleName =
      TitleName(title.fold(name.value)(_.value + " " + name.value))

  opaque type ModId = String
  object ModId extends OpaqueString[ModId]

  object patron:
    opaque type PatronColor = Int
    object PatronColor extends OpaqueInt[PatronColor]

    private val monthsToColor: List[(Int, PatronColor)] =
      List(1 -> 1, 2 -> 2, 3 -> 3, 6 -> 4, 9 -> 5, 12 -> 6, 24 -> 7, 36 -> 8, 48 -> 9, 60 -> 10)
    private val monthsToColorReverse: List[(Int, PatronColor)] = monthsToColor.reverse

    def autoColor(months: Int, lifetime: Boolean): PatronColor =
      if lifetime then 10
      else
        monthsToColorReverse
          .collectFirst:
            case (m, color) if months >= m => color
          .|(1)
