package net.bytebuddy.description.method;

import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.test.utility.MockitoRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MethodListExplicitTest {

    @Rule
    public TestRule mockitoRule = new MockitoRule(this);

    @Mock
    private MethodDescription firstMethodDescription, secondMethodDescription;

    private MethodList methodList;

    @Before
    public void setUp() throws Exception {
        methodList = new MethodList.Explicit(Arrays.asList(firstMethodDescription, secondMethodDescription));
    }

    @Test
    public void testMethodList() throws Exception {
        assertThat(methodList.size(), is(2));
        assertThat(methodList.get(0), is(firstMethodDescription));
        assertThat(methodList.get(1), is(secondMethodDescription));
    }

    @Test
    public void testMethodListFilter() throws Exception {
        @SuppressWarnings("unchecked")
        ElementMatcher<? super MethodDescription> matcher = mock(ElementMatcher.class);
        when(matcher.matches(firstMethodDescription)).thenReturn(true);
        methodList = methodList.filter(matcher);
        assertThat(methodList.size(), is(1));
        assertThat(methodList.getOnly(), is(firstMethodDescription));
    }

    @Test(expected = IllegalStateException.class)
    public void testGetOnly() throws Exception {
        methodList.getOnly();
    }

    @Test
    public void testSubList() throws Exception {
        assertThat(methodList.subList(0, 1), is((MethodList) new MethodList.Explicit(Collections.singletonList(firstMethodDescription))));
    }
}
