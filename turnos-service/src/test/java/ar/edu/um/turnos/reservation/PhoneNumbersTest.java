package ar.edu.um.turnos.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @Test
    void removesSpacesHyphensParenthesesAndDots() {
        assertThat(PhoneNumbers.normalize("+54 9 (261) 555-12.34")).contains("+5492615551234");
    }

    @Test
    void acceptsBetween7And15Digits() {
        assertThat(PhoneNumbers.normalize("1234567")).contains("1234567");
        assertThat(PhoneNumbers.normalize("123456789012345")).contains("123456789012345");
        assertThat(PhoneNumbers.normalize("123456")).isEmpty();
        assertThat(PhoneNumbers.normalize("1234567890123456")).isEmpty();
    }

    @Test
    void rejectsLettersAndMisplacedPlus() {
        assertThat(PhoneNumbers.normalize("261-ABC-1234")).isEmpty();
        assertThat(PhoneNumbers.normalize("261+5551234")).isEmpty();
        assertThat(PhoneNumbers.normalize(null)).isEmpty();
    }
}
