module mylib
  implicit none
contains
  subroutine scale(n, a, x)
    integer, intent(in) :: n
    real(8), intent(in) :: a
    real(8), intent(inout) :: x(n)
    x = a * x
  end subroutine scale

  real(8) function norm2sq(n, x)
    integer, intent(in) :: n
    real(8), intent(in) :: x(n)
    norm2sq = sum(x * x)
  end function norm2sq
end module mylib
